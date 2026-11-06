package com.livehub.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livehub.app.di.AppContainer
import com.livehub.app.ui.components.BiliLoginUi
import com.livehub.app.sync.SyncController
import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import com.livehub.platform.api.PlatformException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The single ViewModel backing the app shell.
 *
 * It owns the navigation stack, the loaded pages and the follow list, and delegates all
 * platform and sync work to the container. Everything asynchronous runs in
 * [viewModelScope]; a new request cancels the previous one for the same slot so rapid
 * tab switches cannot interleave stale results.
 */
class MainViewModel(private val container: AppContainer) : ViewModel() {

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  private var roomsJob: Job? = null
  private var subCategoriesJob: Job? = null
  private var searchJob: Job? = null
  private var playbackJob: Job? = null
  private var danmakuJob: Job? = null
  private var pairingCountdownJob: Job? = null
  private var nextCursor: String? = null
  private var searchCursor: String? = null

  init {
    val preferences = container.preferences
    val settings = preferences.settings
    val platform = Platform.fromId(settings.lastPlatform) ?: Platform.DOUYU
    val themeMode = when (settings.themeMode.lowercase()) {
      "dark" -> com.livehub.core.designsystem.ThemeMode.Dark
      "light" -> com.livehub.core.designsystem.ThemeMode.Light
      else -> com.livehub.core.designsystem.ThemeMode.System
    }
    _state.update {
      it.copy(
        platform = platform,
        themeMode = themeMode,
        darkTheme = themeMode == com.livehub.core.designsystem.ThemeMode.Dark,
        danmakuEnabled = settings.danmakuEnabled,
        danmakuOpacity = settings.danmakuOpacity,
        danmakuFontScale = settings.danmakuFontScale,
        danmakuAreaFraction = settings.danmakuAreaFraction,
        landscapeDanmakuFontScale = settings.landscapeDanmakuFontScale,
        blockedKeywords = settings.blockedKeywords,
        deviceName = preferences.deviceName,
        searchHistory = settings.searchHistory,
        followed = container.libraryStore.followed(),
        roomGroups = container.libraryStore.memberships(),
        followGroups = container.libraryStore.groups(),
        groupRoomOrder = container.libraryStore.groups()
          .associate { group -> group.id to container.libraryStore.roomsInGroup(group.id) },
        favorites = container.libraryStore.favorites(),
        peers = preferences.trustedPeers(),
        syncLog = container.syncEngine.logEntries(),
        bilibiliLogin = com.livehub.app.ui.components.BiliLoginUi(
          loggedIn = container.bilibiliAuth.isLoggedIn(),
        ),
        douyinLoggedIn = container.douyinCookieStore.isLoggedIn(),
      )
    }
    loadCategories(platform)
  }

  fun onAction(action: UiAction) {
    when (action) {
      is UiAction.SelectPlatform -> selectPlatform(action.platform)
      is UiAction.SelectCategory -> selectCategory(action.category)
      is UiAction.SelectSubCategory -> selectSubCategory(action.category)
      is UiAction.SetFollowOrder -> setFollowOrder(action.keys)
      is UiAction.SetGroupOrder -> setGroupOrder(action.groupId, action.keys)
      is UiAction.SelectFollowGroup -> _state.update { it.copy(selectedFollowGroupId = action.groupId) }
      is UiAction.CreateFollowGroup -> createFollowGroup(action.name)
      is UiAction.RenameFollowGroup -> renameFollowGroup(action.groupId, action.name)
      is UiAction.DeleteFollowGroup -> deleteFollowGroup(action.groupId)
      is UiAction.ReorderFollowGroups -> reorderFollowGroups(action.keys)
      is UiAction.SetRoomGroups -> setRoomGroups(action.streamer, action.groupIds)
      is UiAction.AddRoomsToGroups -> addRoomsToGroups(action.rooms, action.groupIds)
      is UiAction.ToggleFavorite -> toggleFavorite(action.category)
      is UiAction.SetFavoriteOrder -> setFavoriteOrder(action.keys)
      is UiAction.ShowMessage -> _state.update { it.copy(message = action.text) }
      UiAction.LoadMore -> loadMore()
      UiAction.Refresh -> refresh()
      is UiAction.OpenRoom -> openRoom(action.streamer)
      UiAction.ClosePlayer -> closePlayer()
      is UiAction.SelectVariant -> selectVariant(action.variant)
      is UiAction.SelectQuality -> selectQuality(action.quality, line = null)
      is UiAction.SelectLine -> selectQuality(quality = null, line = action.line)
      is UiAction.PlayerFailed -> _state.update { it.copy(playerError = action.message) }
      is UiAction.ToggleFollow -> toggleFollow(action.streamer)
      is UiAction.UpdateQuery -> {
        if (action.query.isBlank()) {
          // Emptying the field ends the search that was on screen. Without this the 清空 button
          // in the "没有找到" state cleared the text but left `lastSearchQuery` behind, so the
          // page kept announcing a result for a keyword no longer in the field. An in-flight
          // search for the erased keyword goes too, and the flags it owns have to be reset by
          // hand — a cancelled job never reaches its own `loading = false`.
          searchJob?.cancel()
          searchCursor = null
          _state.update {
            it.copy(
              searchQuery = action.query,
              lastSearchQuery = "",
              searchResults = emptyList(),
              searchHasMore = false,
              loading = false,
              refreshing = false,
              loadingMore = false,
              error = null,
            )
          }
        } else {
          _state.update { it.copy(searchQuery = action.query) }
        }
      }
      UiAction.SubmitSearch -> submitSearch()
      UiAction.LoadMoreSearch -> loadMoreSearch()
      UiAction.ClearSearchHistory -> clearSearchHistory()
      is UiAction.UseHistory -> {
        _state.update { it.copy(searchQuery = action.keyword) }
        submitSearch()
      }
      UiAction.Back -> back()
      is UiAction.Navigate -> navigate(action.screen)
      is UiAction.ToggleDanmaku -> setDanmakuEnabled(action.enabled)
      is UiAction.SetDanmakuAreaFraction -> setDanmakuAreaFraction(action.fraction)
      is UiAction.SetDanmakuFontScale -> setDanmakuFontScale(action.scale)
      is UiAction.SetDanmakuOpacity -> setDanmakuOpacity(action.opacity)
      is UiAction.SetLandscapeDanmakuFontScale -> setLandscapeDanmakuFontScale(action.scale)
      is UiAction.SetBlockedKeywords -> setBlockedKeywords(action.keywords)
      UiAction.RefreshPeers -> refreshPeers()
      is UiAction.UnpairPeer -> unpair(action.deviceId)
      is UiAction.PairWithCode -> pairWithCode(action.code)
      UiAction.RefreshPairingCode -> refreshPairingCode()
      UiAction.StartDiscovery -> startDiscovery()
      UiAction.SyncNow -> syncNow()
      UiAction.ToggleTheme -> {
        // Cycles like the reference client: system → dark → light → system, so all three
        // states stay reachable. A two-state boolean made Light mode impossible to select.
        val next = when (_state.value.themeMode) {
          com.livehub.core.designsystem.ThemeMode.System -> com.livehub.core.designsystem.ThemeMode.Dark
          com.livehub.core.designsystem.ThemeMode.Dark -> com.livehub.core.designsystem.ThemeMode.Light
          com.livehub.core.designsystem.ThemeMode.Light -> com.livehub.core.designsystem.ThemeMode.System
        }
        setThemeMode(next)
      }
      is UiAction.SetThemeMode -> setThemeMode(action.mode)
      UiAction.StartBilibiliLogin -> startBilibiliLogin()
      UiAction.CancelBilibiliLogin -> cancelBilibiliLogin()
      UiAction.LogoutBilibili -> logoutBilibili()
      is UiAction.DouyinLoggedIn -> douyinLoggedIn(action.cookie)
      UiAction.LogoutDouyin -> logoutDouyin()
      UiAction.DismissMessage -> _state.update { it.copy(message = null, error = null) }
    }
  }

  // ------------------------------------------------------------------ navigation

  /**
   * Screens to return to when leaving the player or the search screen.
   *
   * Both used to hardcode Home, so opening a room from the follow list and closing it
   * dropped the user on the browse tab with their position lost. These restore the origin.
   */
  private var playerReturnScreen: Screen = Screen.Home
  private var searchReturnScreen: Screen = Screen.Home

  private fun navigate(screen: Screen) {
    val current = _state.value.screen
    // Remember where search and the player were entered from, in the same spirit as the
    // reference client's `playerReturnScreen` / `searchReturnScreen`.
    when (screen) {
      Screen.Player -> playerReturnScreen = current
      Screen.Search -> searchReturnScreen = current
      else -> Unit
    }
    _state.update { it.copy(screen = screen) }
    when (screen) {
      Screen.Follow -> refreshFollowStatus()
      Screen.Sync -> {
        container.syncController.start()
        refreshPeers()
        startDiscovery()
        // Show a fresh code on arrival: the device the user just picked up is the one they
        // want the matching code from, and a stale code from a previous visit may already
        // have expired.
        val code = container.syncController.beginPairing()
        _state.update {
          it.copy(
            localAddress = container.syncController.serverAddress.orEmpty(),
            discoveredPeers = container.syncController.discoveredPeers(),
            pairingCode = code,
            pairingCodeSecondsLeft = container.syncController.pairingCodeSecondsLeft(),
          )
        }
        startPairingCountdown()
      }
      else -> Unit
    }
  }

  private fun back() {
    val current = _state.value.screen
    val target = when (current) {
      Screen.Home -> return
      Screen.Player -> playerReturnScreen.takeIf { it != Screen.Player } ?: Screen.Home
      Screen.Search -> searchReturnScreen.takeIf { it != Screen.Search } ?: Screen.Home
      else -> Screen.Home
    }
    if (current == Screen.Player) {
      danmakuJob?.cancel()
      playbackJob?.cancel()
    }
    _state.update {
      it.copy(
        screen = target,
        room = null,
        variants = emptyList(),
        selectedVariant = null,
        danmaku = if (current == Screen.Player) emptyList() else it.danmaku,
      )
    }
  }

  // ------------------------------------------------------------------- platform

  private fun selectPlatform(platform: Platform) {
    if (platform == _state.value.platform) return
    container.preferences.setLastPlatform(platform)
    // Search results belong to the platform that produced them: leaving them on screen
    // after a switch labelled 斗鱼 rooms with 虎牙's name.
    searchJob?.cancel()
    searchCursor = null
    _state.update {
      it.copy(
        platform = platform,
        categories = emptyList(),
        selectedCategory = null,
        subCategories = emptyList(),
        selectedSubCategory = null,
        rooms = emptyList(),
        searchResults = emptyList(),
        searchHasMore = false,
        lastSearchQuery = "",
        screen = Screen.Home,
        error = null,
      )
    }
    nextCursor = null
    loadCategories(platform)
  }

  private fun loadCategories(platform: Platform) {
    roomsJob?.cancel()
    roomsJob = viewModelScope.launch {
      _state.update { it.copy(loading = true, error = null) }
      try {
        val categories = container.platformRepository.categories(platform)
        // The aggregate entry leads the row *and* is where the app opens.
        //
        // Landing on the first real partition meant a cold start showed 英雄联盟 on all three
        // platforms that ship a catalogue — the user had to pick one of 400+ partitions before
        // seeing anything, which is the opposite of what a browse-first home screen is for.
        // The aggregate is the "先随便看看" answer, so it is the default.
        //
        // It has no group and no third level, so the group chip reads 全部分类 (see
        // `CategoryLevels.aggregateLabel`) and the third chip row does not render. That is the
        // intended shape of this state, not a missing selection.
        val first = categories.firstOrNull()
        _state.update {
          it.copy(
            categories = categories,
            selectedCategory = first,
          )
        }
        if (first != null) {
          // Still fetched for whatever ends up selected: a platform without an aggregate (or a
          // build whose catalogue asset is missing) auto-selects a real partition, and the row
          // has to arrive with it rather than after the user taps it. See [loadSubCategories].
          loadSubCategories(platform, first)
          loadRooms(platform, first, reset = true)
        } else {
          _state.update { it.copy(loading = false) }
        }
      } catch (error: PlatformException) {
        _state.update { it.copy(loading = false, error = error.message) }
      } catch (error: Exception) {
        _state.update { it.copy(loading = false, hasNextPage = false, error = error.message ?: "加载分区失败") }
      }
    }
  }

  /**
   * Opens a category, then asks the platform whether it has a third level.
   *
   * Only Douyu publishes one. When it answers with sub-categories the home screen shows a
   * third chip row; selecting a chip narrows the list further without re-fetching the
   * parent.
   */
  private fun selectCategory(category: Category) {
    _state.update {
      it.copy(
        selectedCategory = category,
        selectedSubCategory = null,
        subCategories = emptyList(),
        rooms = emptyList(),
      )
    }
    nextCursor = null
    loadRooms(_state.value.platform, category, reset = true)
    loadSubCategories(_state.value.platform, category)
  }

  /**
   * Fetches the third level of [category] and publishes it as the extra chip row.
   *
   * This runs for every category the app *selects*, not just for one the user tapped.
   * [loadCategories] picks the first category on its own — on a cold start and on every
   * platform switch — and the children used to be fetched only from [selectCategory], so a
   * fresh launch showed a two level selector with no third row even though Douyu had one
   * waiting. The only way to reveal it was to tap the chip that was already selected, which
   * looks like a no-op. The reference client fetches on `selectedCate2?.id` with a
   * `LaunchedEffect`, which fires for the initial selection too.
   */
  private fun loadSubCategories(platform: Platform, category: Category) {
    subCategoriesJob?.cancel()
    subCategoriesJob = viewModelScope.launch {
      val children = runCatching {
        container.platformRepository.subCategories(platform, category.id)
      }.getOrDefault(emptyList())
      // A slow answer for a category the user has already left must not repopulate the row.
      _state.update { if (it.selectedCategory?.key == category.key) it.copy(subCategories = children) else it }
    }
  }

  /**
   * Narrows the list to a third level category, or widens it back to the parent when
   * [category] is null.
   *
   * A third level never replaces [UiState.selectedCategory]: the parent chip keeps its
   * selection so the user can still see which category they are inside, and the reference
   * client keeps `selectedCate2` alive alongside `selectedCate3` for the same reason.
   */
  private fun selectSubCategory(category: Category?) {
    val parent = _state.value.selectedCategory ?: return
    _state.update { it.copy(selectedSubCategory = category, rooms = emptyList()) }
    nextCursor = null
    loadRooms(_state.value.platform, category ?: parent, reset = true)
  }

  private fun loadRooms(platform: Platform, category: Category, reset: Boolean, pullToRefresh: Boolean = false) {
    roomsJob?.cancel()
    roomsJob = viewModelScope.launch {
      _state.update {
        when {
          // A pull keeps the current grid on screen and only shows the indicator; showing
          // the skeleton as well made the list flash empty on every refresh.
          pullToRefresh -> it.copy(refreshing = true, error = null)
          reset -> it.copy(loading = true, error = null)
          else -> it.copy(loadingMore = true)
        }
      }
      try {
        val page = container.platformRepository.rooms(
          platform = platform,
          categoryId = category.id,
          cursor = if (reset) null else nextCursor,
          slug = category.slug,
        )
        nextCursor = page.nextCursor
        _state.update {
          val merged = if (reset) page.rooms else it.rooms + page.rooms
          it.copy(
            rooms = merged.distinctBy { room -> room.key },
            loading = false,
            loadingMore = false,
            refreshing = false,
            hasNextPage = page.hasMore,
          )
        }
      } catch (error: Exception) {
        _state.update {
          it.copy(
            loading = false,
            loadingMore = false,
            refreshing = false,
            error = error.message ?: "加载直播列表失败",
          )
        }
      }
    }
  }

  /**
   * The category the room list is really filtered by: the third level when one is picked,
   * otherwise the second level.
   *
   * A sub-category narrows the list *without* replacing [UiState.selectedCategory] — the
   * parent chip has to stay selected, otherwise the user loses their place in the two level
   * selector. That makes `selectedCategory` the wrong thing to read for a room request:
   * [loadMore] and [refresh] both did, so scrolling inside 三级分类 appended rooms from the
   * whole 二级分类 and a pull-to-refresh silently threw the third level away. The reference
   * client resolves `selectedCate3 ?: selectedCate2` on every page for the same reason.
   */
  private fun activeCategory(state: UiState): Category? =
    state.selectedSubCategory ?: state.selectedCategory

  private fun loadMore() {
    val current = _state.value
    val category = activeCategory(current) ?: return
    if (current.loading || current.loadingMore || current.refreshing || nextCursor == null) return
    loadRooms(current.platform, category, reset = false)
  }

  private fun refresh() {
    val current = _state.value
    val category = activeCategory(current)
    if (category == null) {
      loadCategories(current.platform)
    } else {
      nextCursor = null
      loadRooms(current.platform, category, reset = true, pullToRefresh = current.screen == Screen.Home)
    }
    if (current.screen == Screen.Follow) refreshFollowStatus(pullToRefresh = true)
  }

  // --------------------------------------------------------------------- search

  private fun submitSearch() {
    val query = _state.value.searchQuery.trim()
    if (query.isEmpty()) {
      // Telling the user why nothing happened beats a silent no-op.
      _state.update { it.copy(message = "请输入要搜索的主播或直播间名称") }
      return
    }
    container.preferences.addSearchHistory(query)
    searchJob?.cancel()
    searchCursor = null
    searchJob = viewModelScope.launch {
      _state.update { current ->
        // Re-running the *same* keyword is a refresh: keep the list on screen and turn the
        // indicator instead of blanking it to the skeleton. A different keyword replaces the
        // list, because those rooms were searched for something else — leaving them up showed
        // the previous keyword's rooms under the new keyword's name.
        val sameKeyword = current.lastSearchQuery == query
        current.copy(
          screen = Screen.Search,
          lastSearchQuery = query,
          refreshing = sameKeyword && current.searchResults.isNotEmpty(),
          loading = !sameKeyword || current.searchResults.isEmpty(),
          loadingMore = false,
          searchResults = if (sameKeyword) current.searchResults else emptyList(),
          searchHasMore = false,
          error = null,
          searchHistory = container.preferences.settings.searchHistory,
        )
      }
      try {
        val page = container.platformRepository.search(_state.value.platform, query)
        searchCursor = page.nextCursor
        _state.update {
          it.copy(
            searchResults = page.rooms,
            searchHasMore = page.hasMore,
            loading = false,
            refreshing = false,
          )
        }
      } catch (cancelled: CancellationException) {
        // A superseded search must not report its own cancellation as a failure: the job that
        // replaced it owns the state now. `CancellationException` extends `Exception`, so
        // without this clause `searchJob?.cancel()` above set `error` to "Job was cancelled".
        throw cancelled
      } catch (error: Exception) {
        searchCursor = null
        _state.update {
          // The results on screen no longer answer the keyword that was asked about, so they
          // go. Keeping them also hid the failure: the search screen only shows its error
          // state when there is nothing else to show, so a failed second search looked like a
          // successful one over the first keyword's rooms.
          it.copy(
            searchResults = emptyList(),
            searchHasMore = false,
            loading = false,
            refreshing = false,
            error = error.message ?: "搜索失败",
          )
        }
      }
    }
  }

  /** Appends the next page of search results, if the platform reported one. */
  private fun loadMoreSearch() {
    val current = _state.value
    // The keyword that produced `searchCursor`, not what the field says now: editing the
    // field without submitting used to send the *new* text together with the *old* cursor,
    // so the appended page belonged to a search the user never made.
    val query = current.lastSearchQuery.trim()
    val cursor = searchCursor
    if (query.isEmpty() || cursor == null || current.loading || current.loadingMore) return

    searchJob = viewModelScope.launch {
      _state.update { it.copy(loadingMore = true) }
      try {
        val page = container.platformRepository.search(current.platform, query, cursor)
        searchCursor = page.nextCursor
        _state.update {
          it.copy(
            searchResults = (it.searchResults + page.rooms).distinctBy { room -> room.key },
            searchHasMore = page.hasMore,
            loadingMore = false,
          )
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        _state.update {
          it.copy(loadingMore = false, message = error.message ?: "加载更多失败")
        }
      }
    }
  }

  private fun clearSearchHistory() {
    container.preferences.clearSearchHistory()
    _state.update { it.copy(searchHistory = emptyList()) }
  }

  // -------------------------------------------------------------------- follows

  /**
   * Every follow-scoped read, in one place.
   *
   * The library is four record kinds in one set, and the UI shows all four at once (list, group
   * row, per-room group chips, pinned categories). Re-reading them together is what keeps a
   * group rename from leaving a stale id in the row, so every mutation ends here rather than
   * patching `_state` field by field.
   */
  private fun refreshLibrary() {
    val store = container.libraryStore
    val groups = store.groups()
    _state.update {
      it.copy(
        followed = store.followed(),
        roomGroups = store.memberships(),
        followGroups = groups,
        groupRoomOrder = groups.associate { group -> group.id to store.roomsInGroup(group.id) },
        favorites = store.favorites(),
      )
    }
  }

  private fun toggleFollow(streamer: Streamer) {
    val engine = container.syncEngine
    val store = container.libraryStore
    val deviceId = container.preferences.deviceId
    val now = System.currentTimeMillis()
    val hlc = engine.nextHlc()
    store.toggle(streamer, hlc, deviceId, now)
    val followed = store.followed()
    _state.update {
      it.copy(
        followed = followed,
        roomGroups = store.memberships(),
        groupRoomOrder = store.groups().associate { group -> group.id to store.roomsInGroup(group.id) },
        message = if (followed.any { f -> f.key == streamer.key }) "已关注 ${streamer.streamerName}" else "已取消关注",
      )
    }
  }

  private fun refreshFollowStatus(pullToRefresh: Boolean = false) {
    val followed = container.libraryStore.followed()
    if (followed.isEmpty()) {
      _state.update { it.copy(followed = emptyList(), refreshing = false) }
      return
    }
    viewModelScope.launch {
      if (pullToRefresh) _state.update { it.copy(refreshing = true) }
      val refreshed = followed.map { streamer ->
        runCatching {
          container.platformRepository.liveStatus(streamer.platform, streamer.roomId)
        }.getOrNull()?.let { status ->
          streamer.copy(
            isLive = status.isLive,
            title = status.title ?: streamer.title,
            viewerCount = status.viewerCount,
            coverUrl = status.coverUrl ?: streamer.coverUrl,
            updatedAt = System.currentTimeMillis(),
          )
        } ?: streamer
      }
      _state.update { it.copy(followed = refreshed, refreshing = false) }
    }
  }

  // --------------------------------------------------------------------- player

  private fun openRoom(streamer: Streamer) {
    playbackJob?.cancel()
    // Remember where the room was opened from so closing it returns there.
    playerReturnScreen = _state.value.screen.takeIf { it != Screen.Player } ?: Screen.Home
    _state.update {
      it.copy(
        screen = Screen.Player,
        room = streamer,
        variants = emptyList(),
        selectedVariant = null,
        lines = emptyList(),
        selectedLine = null,
        loading = true,
        // A retry must not start under the previous attempt's error: the screen renders the
        // error banner before it looks at `loading`, so a stale message would hide the fact
        // that a fresh resolve is already in flight. The same goes for a decoder failure,
        // which is only valid for the URL it was raised against.
        error = null,
        playerError = null,
      )
    }
    playbackJob = viewModelScope.launch {
      // Danmaku is independent of playback: a chat stream must keep working even when
      // the video URL cannot be resolved, otherwise a failed stream shows "正在连接弹幕"
      // forever.
      startDanmaku(streamer)
      try {
        // Open at the quality the user last picked on this platform. The preference is
        // advisory: an adapter that does not offer it falls back to its own default.
        val remembered = container.preferences.settings.preferredQuality[streamer.platform.id]
        val source = container.platformRepository.playback(
          platform = streamer.platform,
          roomId = streamer.roomId,
          preferredQuality = remembered,
        )
        val preferred = source.defaultVariant
        _state.update {
          it.copy(
            variants = source.variants,
            selectedVariant = preferred,
            lines = source.lines,
            selectedLine = source.selectedLine,
            room = source.room.takeIf { room -> room.streamerName.isNotBlank() } ?: streamer,
            loading = false,
          )
        }
      } catch (error: Exception) {
        _state.update {
          it.copy(loading = false, error = error.message ?: "无法播放该直播间")
        }
      }
    }
  }

  /**
   * Switches the quality or the line of the room that is already open.
   *
   * Douyu needs a fresh signed `getH5Play` call per `(rate, cdn)` pair, so a change cannot
   * be applied to the current URL alone; the whole source is resolved again with the new
   * preference. The previously selected value is kept if the platform does not offer it,
   * and a failed re-resolve leaves the stream that is already playing untouched.
   */
  private fun selectQuality(quality: Int?, line: String?) {
    val current = _state.value
    val room = current.room ?: return
    val nextQuality = quality ?: current.selectedVariant?.qualityCode
    val nextLine = line ?: current.selectedLine

    if (quality == null && line == null) return

    // Re-picking what is already playing changes nothing, and on a platform with a single
    // quality (Huya) that chip is the only one there is: without this, every tap on it would
    // re-download the room page just to resolve the URL the player already holds. A decoder
    // failure is the exception — the URL is dead, so the tap has to resolve a fresh one.
    if (line == null && quality == current.selectedVariant?.qualityCode && current.playerError == null) {
      return
    }

    // Remember the choice per platform, so the next room opens at the quality the user
    // picked instead of resetting to 原画 every time. The preference map existed with a
    // setter but nothing ever wrote or read it.
    if (quality != null) {
      container.preferences.setPreferredQuality(room.platform, quality)
    }

    // Picking a variant that is already resolved needs no network round trip, unless the
    // platform also publishes a separate line axis (Douyu), where the line has to match too.
    if (line == null && nextQuality != null) {
      current.variants.firstOrNull { it.qualityCode == nextQuality }?.let { resolved ->
        if (current.lines.isEmpty()) {
          _state.update { it.copy(selectedVariant = resolved, playerError = null) }
          return
        }
      }
    }

    playbackJob?.cancel()
    playbackJob = viewModelScope.launch {
      _state.update { it.copy(switchingQuality = true) }
      try {
        val source = container.platformRepository.playback(
          platform = room.platform,
          roomId = room.roomId,
          preferredQuality = nextQuality,
          preferredLine = nextLine,
        )
        _state.update {
          it.copy(
            variants = source.variants,
            selectedVariant = source.defaultVariant ?: source.variants.firstOrNull(),
            lines = source.lines,
            selectedLine = source.selectedLine,
            switchingQuality = false,
            // The new URL replaces the one the decoder rejected.
            playerError = null,
          )
        }
      } catch (error: Exception) {
        _state.update {
          it.copy(switchingQuality = false, message = error.message ?: "切换失败")
        }
      }
    }
  }

  private fun closePlayer() {
    playbackJob?.cancel()
    danmakuJob?.cancel()
    val target = playerReturnScreen.takeIf { it != Screen.Player } ?: Screen.Home
    _state.update {
      it.copy(
        screen = target,
        room = null,
        variants = emptyList(),
        selectedVariant = null,
        lines = emptyList(),
        selectedLine = null,
        danmaku = emptyList(),
        playerError = null,
      )
    }
  }

  /**
   * Replaces the stored follow order with [keys].
   *
   * The view resolves the drop against the full list before calling this
   * ([com.livehub.app.di.FollowOrdering.moveWithin]); by the time it arrives the only thing left
   * to do is stamp it, so a drag can never write a *filtered* order into the store.
   */
  private fun setFollowOrder(keys: List<String>) {
    if (keys.isEmpty()) return
    container.libraryStore.reorderRooms(
      roomKeys = keys,
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = System.currentTimeMillis(),
    )
    refreshLibrary()
  }

  private fun setGroupOrder(groupId: String, keys: List<String>) {
    if (keys.isEmpty()) return
    container.libraryStore.reorderWithinGroup(
      groupId = groupId,
      roomKeys = keys,
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = System.currentTimeMillis(),
    )
    refreshLibrary()
  }

  private fun createFollowGroup(name: String) {
    val group = container.libraryStore.createGroup(
      name = name,
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = System.currentTimeMillis(),
    )
    if (group == null) {
      _state.update { it.copy(message = "分组名不能为空、不能超过 20 字，也不能和现有分组重名") }
      return
    }
    refreshLibrary()
    _state.update { it.copy(selectedFollowGroupId = group.id, message = "已创建分组「${group.name}」") }
  }

  private fun renameFollowGroup(groupId: String, name: String) {
    val ok = container.libraryStore.renameGroup(
      id = groupId,
      name = name,
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = System.currentTimeMillis(),
    )
    if (!ok) {
      _state.update { it.copy(message = "分组名不能为空、不能超过 20 字，也不能和现有分组重名") }
      return
    }
    refreshLibrary()
  }

  private fun deleteFollowGroup(groupId: String) {
    val name = _state.value.followGroups.firstOrNull { it.id == groupId }?.name
    container.libraryStore.deleteGroup(
      groupId = groupId,
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = System.currentTimeMillis(),
    )
    refreshLibrary()
    _state.update {
      it.copy(
        // A deleted group cannot stay selected: the row would render no chip as active.
        selectedFollowGroupId = it.selectedFollowGroupId?.takeIf { id -> id != groupId },
        message = if (name != null) "已删除「$name」，组内主播已回到未分类" else "已删除分组",
      )
    }
  }

  private fun reorderFollowGroups(keys: List<String>) {
    if (keys.isEmpty()) return
    container.libraryStore.reorderGroups(
      keys = keys,
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = System.currentTimeMillis(),
    )
    refreshLibrary()
  }

  private fun setRoomGroups(streamer: Streamer, groupIds: Set<String>) {
    container.libraryStore.setMembership(
      streamer = streamer,
      targetGroupIds = groupIds,
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = System.currentTimeMillis(),
    )
    refreshLibrary()
  }

  /**
   * Files several rooms into [groupIds] at once.
   *
   * Adds rather than replaces — see [UiAction.AddRoomsToGroups]. The count in the message is what
   * the store reports, not how many rooms were selected: selecting five rooms for a group three of
   * them were already in should say "3" not "5".
   */
  private fun addRoomsToGroups(rooms: List<Streamer>, groupIds: Set<String>) {
    if (rooms.isEmpty() || groupIds.isEmpty()) return
    val added = container.libraryStore.addRoomsToGroups(
      streamers = rooms,
      groupIds = groupIds,
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = System.currentTimeMillis(),
    )
    refreshLibrary()
    val names = _state.value.followGroups.filter { it.id in groupIds }.joinToString("、") { it.name }
    _state.update {
      it.copy(
        message = if (added == 0) {
          "这些主播已经在「$names」里了"
        } else {
          "已把 $added 位主播放进「$names」"
        },
      )
    }
  }

  /**
   * Pins or unpins [category].
   *
   * Refusals are explained rather than swallowed. The aggregate entry ("全部直播") is the one
   * category a long-press can land on that has no favourite to toggle — it is the whole
   * platform, not a partition — and a tap that does nothing at all reads as a bug.
   */
  private fun toggleFavorite(category: Category) {
    if (category.isAggregate || category.id == Category.ALL_ID) {
      _state.update { it.copy(message = "「${category.name}」是整个平台，不需要收藏") }
      return
    }
    val pinned = container.libraryStore.isFavorite(category)
    val ok = container.libraryStore.toggleFavorite(
      category = category,
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = System.currentTimeMillis(),
    )
    if (!ok) {
      _state.update {
        it.copy(
          message = if (pinned) "取消收藏失败" else "收藏已达上限（100 个），先取消一些",
        )
      }
      return
    }
    refreshLibrary()
    _state.update {
      it.copy(message = if (pinned) "已取消收藏「${category.name}」" else "已收藏「${category.name}」")
    }
  }

  private fun setFavoriteOrder(keys: List<String>) {
    if (keys.isEmpty()) return
    container.libraryStore.reorderFavorites(
      keys = keys,
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = System.currentTimeMillis(),
    )
    refreshLibrary()
  }

  /**
   * Subscribes to the platform danmaku stream for [streamer].
   *
   * Messages are capped so a very busy room cannot grow the state without bound; the
   * overlay only needs the most recent window to animate.
   */
  private fun startDanmaku(streamer: Streamer) {
    danmakuJob?.cancel()
    danmakuJob = viewModelScope.launch {
      runCatching {
        container.platformGateway.danmaku(streamer.platform, streamer.roomId).collect { event ->
          when (event) {
            is com.livehub.platform.api.DanmakuEvent.Message -> _state.update { current ->
              val next = (current.danmaku + event.message).takeLast(MAX_DANMAKU_BUFFER)
              current.copy(danmaku = next)
            }
            else -> Unit
          }
        }
      }
    }
  }

  /**
   * Applies an already-resolved variant.
   *
   * Used when the UI hands over a concrete [StreamVariant] on a platform whose variant list is
   * a flat list of resolved URLs (Bilibili resolves one URL per quality), so no network round
   * trip is needed to switch.
   */
  private fun selectVariant(variant: StreamVariant) {
    // Remember it for the next room on the same platform, matching what selectQuality does
    // for the platforms that resolve one URL per rate.
    val platform = _state.value.room?.platform
    val code = variant.qualityCode
    if (platform != null && code != null) {
      container.preferences.setPreferredQuality(platform, code)
    }
    _state.update { it.copy(selectedVariant = variant, playerError = null) }
  }

  // ---------------------------------------------------------------- preferences

  private fun setDanmakuEnabled(enabled: Boolean) {
    container.preferences.setDanmakuEnabled(enabled)
    _state.update { it.copy(danmakuEnabled = enabled) }
  }

  /**
   * Persists and applies the theme choice.
   *
   * `setThemeMode` already existed on [UserPreferences] but nothing ever called it, so the
   * selection died on every restart.
   */
  private fun setThemeMode(mode: com.livehub.core.designsystem.ThemeMode) {
    container.preferences.setThemeMode(
      when (mode) {
        com.livehub.core.designsystem.ThemeMode.System -> "system"
        com.livehub.core.designsystem.ThemeMode.Dark -> "dark"
        com.livehub.core.designsystem.ThemeMode.Light -> "light"
      },
    )
    _state.update {
      it.copy(
        themeMode = mode,
        // Kept in sync for callers that only know "is it dark right now"; the theme
        // itself is driven by `themeMode` because System is a third state.
        darkTheme = mode == com.livehub.core.designsystem.ThemeMode.Dark,
      )
    }
  }

  /** Danmaku area and font scale are persisted so the choice survives a restart. */
  private fun setDanmakuAreaFraction(fraction: Float) {
    val clamped = fraction.coerceIn(0.1f, 1f)
    container.preferences.update { it.copy(danmakuAreaFraction = clamped) }
    _state.update { it.copy(danmakuAreaFraction = clamped) }
  }

  private fun setDanmakuFontScale(scale: Float) {
    val clamped = scale.coerceIn(0.6f, 2f)
    container.preferences.update { it.copy(danmakuFontScale = clamped) }
    _state.update { it.copy(danmakuFontScale = clamped) }
  }

  /** Opacity had a persisted field but no setter, so the overlay was always opaque. */
  private fun setDanmakuOpacity(opacity: Float) {
    val clamped = opacity.coerceIn(0.2f, 1f)
    container.preferences.update { it.copy(danmakuOpacity = clamped) }
    _state.update { it.copy(danmakuOpacity = clamped) }
  }

  private fun setLandscapeDanmakuFontScale(scale: Float) {
    val clamped = scale.coerceIn(0.8f, 2f)
    container.preferences.update { it.copy(landscapeDanmakuFontScale = clamped) }
    _state.update { it.copy(landscapeDanmakuFontScale = clamped) }
  }

  private fun setBlockedKeywords(keywords: List<String>) {
    val normalized = keywords
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .distinctBy { it.lowercase() }
      .take(50)
    container.preferences.setBlockedKeywords(normalized)
    _state.update { it.copy(blockedKeywords = normalized) }
  }

  // ------------------------------------------------------------- peer discovery

  private var discoveryJob: Job? = null

  /**
   * Runs mDNS discovery for a bounded window and mirrors every sighting into the UI.
   *
   * Discovery is intentionally time-boxed: keeping an mDNS browse running forever
   * drains the battery and the sync screen is the only place that needs it.
   */
  private fun startDiscovery() {
    discoveryJob?.cancel()
    container.syncController.start()
    discoveryJob = viewModelScope.launch {
      _state.update { it.copy(discovering = true) }
      val polling = launch {
        repeat(DISCOVERY_POLLS) {
          kotlinx.coroutines.delay(DISCOVERY_POLL_INTERVAL_MILLIS)
          _state.update {
            it.copy(discoveredPeers = container.syncController.discoveredPeers())
          }
        }
      }
      polling.join()
      _state.update {
        it.copy(
          discovering = false,
          discoveredPeers = container.syncController.discoveredPeers(),
          message = if (it.discoveredPeers.isEmpty()) {
            "没有发现其他设备。请确认两台设备在同一 Wi-Fi 下，或手动填写对方地址。"
          } else {
            null
          },
        )
      }
    }
  }
  // ------------------------------------------------------------- bilibili login

  private var loginJob: Job? = null

  private fun startBilibiliLogin() {
    loginJob?.cancel()
    loginJob = viewModelScope.launch {
      _state.update {
        it.copy(
          bilibiliLogin = it.bilibiliLogin.copy(
            loading = true,
            qr = null,
            status = "正在获取二维码…",
          ),
        )
      }
      try {
        val qr = container.bilibiliAuth.requestQrCode()
        val bitmap = com.livehub.app.ui.qr.QrCodeRenderer.render(qr.url, sizePx = 512)
        _state.update {
          it.copy(
            bilibiliLogin = it.bilibiliLogin.copy(
              loading = false,
              qr = bitmap,
              status = "请使用哔哩哔哩客户端扫码",
            ),
          )
        }
        pollUntilConfirmed(qr.key)
      } catch (error: Exception) {
        _state.update {
          it.copy(
            bilibiliLogin = it.bilibiliLogin.copy(
              loading = false,
              qr = null,
              status = error.message ?: "获取二维码失败",
            ),
          )
        }
      }
    }
  }

  private suspend fun pollUntilConfirmed(key: String) {
    repeat(LOGIN_POLL_ATTEMPTS) {
      kotlinx.coroutines.delay(LOGIN_POLL_INTERVAL_MILLIS)
      when (val result = runCatching { container.bilibiliAuth.poll(key) }.getOrNull()) {
        null -> Unit
        com.livehub.platform.bilibili.BilibiliAuth.PollResult.Waiting -> Unit
        com.livehub.platform.bilibili.BilibiliAuth.PollResult.Scanned -> _state.update {
          it.copy(bilibiliLogin = it.bilibiliLogin.copy(status = "已扫码，请在手机上确认"))
        }
        com.livehub.platform.bilibili.BilibiliAuth.PollResult.Expired -> {
          _state.update {
            it.copy(bilibiliLogin = it.bilibiliLogin.copy(qr = null, status = "二维码已过期，请重新获取"))
          }
          return
        }
        is com.livehub.platform.bilibili.BilibiliAuth.PollResult.Confirmed -> {
          // BilibiliAuth already wrote the cookies into the store; the record is what carries
          // the clock, and what makes the login travel to the paired device.
          container.credentialStore.set(
            platform = Platform.BILIBILI,
            header = container.bilibiliCookieStore.header(),
            hlc = container.syncEngine.nextHlc(),
            deviceId = container.preferences.deviceId,
            now = System.currentTimeMillis(),
          )
          _state.update {
            it.copy(
              bilibiliLogin = it.bilibiliLogin.copy(loggedIn = true, qr = null, status = "登录成功"),
            )
          }
          return
        }
        is com.livehub.platform.bilibili.BilibiliAuth.PollResult.Failed -> {
          _state.update {
            it.copy(bilibiliLogin = it.bilibiliLogin.copy(qr = null, status = result.message))
          }
          return
        }
      }
    }
    _state.update {
      it.copy(bilibiliLogin = it.bilibiliLogin.copy(qr = null, status = "登录超时，请重试"))
    }
  }

  private fun cancelBilibiliLogin() {
    loginJob?.cancel()
    _state.update {
      it.copy(bilibiliLogin = it.bilibiliLogin.copy(loading = false, qr = null, status = ""))
    }
  }

  private fun logoutBilibili() {
    cancelBilibiliLogin()
    // Through the credential store, so the logout is a tombstone that travels to the paired
    // device instead of a local clear the peer silently refills on the next sync.
    container.credentialStore.set(
      platform = Platform.BILIBILI,
      header = null,
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = System.currentTimeMillis(),
    )
    _state.update {
      it.copy(
        bilibiliLogin = it.bilibiliLogin.copy(loggedIn = false, status = "已退出登录"),
        message = "已退出哔哩哔哩账号",
      )
    }
  }

  /**
   * Stores what the Douyin login sheet captured.
   *
   * The cookie is recorded through [CredentialStore.set], which both merges it into the cookie
   * store the requests read (`ttwid` is already there from negotiation) and stamps the record
   * with a fresh clock so this login wins the next merge. Search reads `isLoggedIn()` on the
   * next call, which is why keyword search works immediately instead of after a restart.
   */
  private fun douyinLoggedIn(cookie: String) {
    val loggedIn = com.livehub.platform.douyin.hasDouyinSession(cookie)
    if (loggedIn) {
      container.credentialStore.set(
        platform = Platform.DOUYIN,
        header = cookie,
        hlc = container.syncEngine.nextHlc(),
        deviceId = container.preferences.deviceId,
        now = System.currentTimeMillis(),
      )
    }
    _state.update {
      it.copy(
        douyinLoggedIn = loggedIn,
        message = if (loggedIn) "抖音登录成功，现在可以按昵称搜索" else "没有取到抖音登录态，请重试",
      )
    }
  }

  private fun logoutDouyin() {
    container.credentialStore.set(
      platform = Platform.DOUYIN,
      header = null,
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = System.currentTimeMillis(),
    )
    _state.update { it.copy(douyinLoggedIn = false, message = "已退出抖音登录") }
  }

  // ----------------------------------------------------------------------- sync

  private fun refreshPeers() {
    _state.update {
      it.copy(
        peers = container.preferences.trustedPeers(),
        syncLog = container.syncEngine.logEntries(),
        localAddress = container.syncController.serverAddress.orEmpty(),
        discoveredPeers = container.syncController.discoveredPeers(),
        pairingCode = container.syncController.pairingCode.orEmpty(),
        pairingCodeSecondsLeft = container.syncController.pairingCodeSecondsLeft(),
      )
    }
  }

  private fun unpair(deviceId: Long) {
    container.syncEngine.unpair(deviceId)
    // Drop the address too: it belongs to a pairing that no longer exists, and leaving it in
    // the persisted table would keep it in play for the next sweep.
    container.syncController.forgetPeer(deviceId)
    refreshPeers()
    _state.update { it.copy(message = "已解除配对") }
  }

  /**
   * Pairs with whichever device is showing [code].
   *
   * The whole flow is the code: candidates come from discovery plus a subnet sweep inside
   * [SyncController.pairByCode], so there is no address for the user to read or retype.
   */
  private fun pairWithCode(code: String) {
    val digits = code.filter(Char::isDigit)
    if (digits.length != 6) {
      _state.update { it.copy(message = "请输入六位匹配码") }
      return
    }
    viewModelScope.launch {
      _state.update { it.copy(pairing = true) }
      // `finally` rather than a plain sequence: any throw between the handshake and the
      // state reads used to leave the flag stuck and 立即同步 permanently disabled.
      val result = try {
        runCatching { container.syncController.pairByCode(digits) }
          .getOrElse { Result.failure(it) }
      } finally {
        _state.update { it.copy(pairing = false) }
      }
      _state.update {
        it.copy(
          peers = container.preferences.trustedPeers(),
          followed = container.libraryStore.followed(),
          message = result.fold(
            onSuccess = { name -> "已与 $name 关联" },
            onFailure = { error -> error.message ?: "配对失败" },
          ),
        )
      }
    }
  }

  /**
   * Shows a new matching code.
   *
   * Re-issuing also drops the countdown back to a full window, so the UI never shows a
   * code that is already dead.
   */
  private fun refreshPairingCode() {
    val code = container.syncController.beginPairing()
    _state.update {
      it.copy(
        pairingCode = code,
        pairingCodeSecondsLeft = container.syncController.pairingCodeSecondsLeft(),
      )
    }
    startPairingCountdown()
  }

  /**
   * Keeps [UiState.pairingCodeSecondsLeft] honest while the screen is open.
   *
   * The value is a snapshot, so without this the screen would keep advertising the code's
   * original five minutes for as long as the user stayed on the page — and the other
   * device would be told "配对码已过期" by a screen still claiming 4 分钟.
   */
  private fun startPairingCountdown() {
    pairingCountdownJob?.cancel()
    pairingCountdownJob = viewModelScope.launch {
      while (true) {
        val left = container.syncController.pairingCodeSecondsLeft()
        _state.update { it.copy(pairingCodeSecondsLeft = left) }
        if (left <= 0) return@launch
        kotlinx.coroutines.delay(1_000)
      }
    }
  }

  private fun syncNow() {
    viewModelScope.launch {
      _state.update { it.copy(syncing = true) }
      val summary = runCatching { container.syncController.syncNow() }
        .getOrElse { error ->
          SyncController.SyncSummary(0, 0, error.message ?: "同步失败")
        }
      _state.update {
        it.copy(
          syncing = false,
          peers = container.preferences.trustedPeers(),
          followed = container.libraryStore.followed(),
          roomGroups = container.libraryStore.memberships(),
          followGroups = container.libraryStore.groups(),
          groupRoomOrder = container.libraryStore.groups()
            .associate { group -> group.id to container.libraryStore.roomsInGroup(group.id) },
          favorites = container.libraryStore.favorites(),
          syncLog = container.syncEngine.logEntries(),
          message = summary.message,
        )
      }
    }
  }

  /** Convenience for the UI: the currently selected category's display name. */
  fun categoryTitle(category: Category?): String = category?.name.orEmpty()

  override fun onCleared() {
    danmakuJob?.cancel()
    pairingCountdownJob?.cancel()
    container.syncController.stop()
  }

  private companion object {
    const val MAX_DANMAKU_BUFFER = 200
    const val DISCOVERY_POLLS = 15
    const val DISCOVERY_POLL_INTERVAL_MILLIS = 2_000L
    const val LOGIN_POLL_ATTEMPTS = 100
    const val LOGIN_POLL_INTERVAL_MILLIS = 2_000L
  }
}
