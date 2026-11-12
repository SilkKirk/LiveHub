package com.livehub.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livehub.app.di.AppContainer
import com.livehub.app.sync.SyncController
import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import com.livehub.platform.api.PlatformException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

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
  private var followsJob: Job? = null
  private var pairingCountdownJob: Job? = null
  private var nextCursor: String? = null
  private var searchCursor: String? = null

  /**
   * Identifies the newest follow-status refresh.
   *
   * A cancelled coroutine still runs its `finally`, so "am I the current refresh?" cannot be
   * answered by the job handle (it has already been replaced). The number is compared instead,
   * which also stops an older refresh from clearing the pull-to-refresh indicator of a newer one.
   */
  private var followsRefreshToken: Int = 0

  /** When the follow list was last checked; see [FOLLOW_REFRESH_MIN_INTERVAL_MILLIS]. */
  private var lastFollowRefreshAt: Long = 0L

  init {
    val preferences = container.preferences
    val settings = preferences.settings
    val platform = Platform.fromId(settings.lastPlatform) ?: Platform.DOUYU
    val themeMode = when (settings.themeMode.lowercase()) {
      "dark" -> com.livehub.core.designsystem.ThemeMode.Dark
      "light" -> com.livehub.core.designsystem.ThemeMode.Light
      else -> com.livehub.core.designsystem.ThemeMode.System
    }
    // Login state is deliberately **not** part of this first snapshot. It lives in two DataStore
    // files of its own and reading it is a synchronous read, so resolving it here put two blocking
    // disk reads on the main thread inside `onCreate` — the same defect round 60 fixed for
    // `UserPreferences`. The flags are only rendered on 设置 and in the search hint, so resolving
    // them one frame later is invisible; blocking the first frame is not.
    _state.update {
      it.copy(
        platform = platform,
        themeMode = themeMode,
        danmakuEnabled = settings.danmakuEnabled,
        danmakuOpacity = settings.danmakuOpacity,
        danmakuFontScale = settings.danmakuFontScale,
        danmakuAreaFraction = settings.danmakuAreaFraction,
        landscapeDanmakuFontScale = settings.landscapeDanmakuFontScale,
        blockedKeywords = settings.blockedKeywords,
        deviceName = preferences.deviceName,
        searchHistory = settings.searchHistory,
        followed = container.followStore.followed(),
        specialFollowKeys = container.followStore.specialKeys(),
        peers = preferences.trustedPeers(),
        syncLog = container.syncEngine.logEntries(),
      )
    }
    loadCategories(platform)
    viewModelScope.launch {
      val bilibili = withContext(Dispatchers.IO) { container.bilibiliAuth.isLoggedIn() }
      val douyin = withContext(Dispatchers.IO) { container.douyinCookieStore.isLoggedIn() }
      _state.update {
        it.copy(
          bilibiliLogin = it.bilibiliLogin.copy(loggedIn = bilibili),
          douyinLoggedIn = douyin,
        )
      }
    }
  }

  fun onAction(action: UiAction) {
    when (action) {
      is UiAction.SelectPlatform -> selectPlatform(action.platform)
      is UiAction.SelectSearchPlatform -> selectSearchPlatform(action.platform)
      is UiAction.SelectCategory -> selectCategory(action.category)
      is UiAction.SelectSubCategory -> selectSubCategory(action.category)
      is UiAction.SetFollowOrder -> setFollowOrder(action.keys)
      is UiAction.ToggleSpecialFollow -> toggleSpecialFollow(action.streamer)
      is UiAction.SetFollowLiveFilter -> _state.update { it.copy(followLiveFilter = action.filter) }
      is UiAction.SetFollowPlatformFilter -> _state.update { it.copy(followPlatformFilter = action.platform) }
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
    // Leaving the sync screen shuts the LAN server down. `SyncController`'s own contract says the
    // server runs "only while the sync screen is open" — it used to keep listening until the
    // process died, because the only `stop()` was in `onCleared`, which nothing called. Stopping
    // here also cancels the pairing window, so a six digit code cannot outlive the screen that
    // displayed it.
    if (current == Screen.Sync && screen != Screen.Sync) stopSyncServer()
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

  /**
   * Stops the LAN server and clears everything the sync screen owns.
   *
   * The UI state goes too: a code or an address left behind would be shown again on the next
   * visit before `beginPairing()` had a chance to issue a new one, and the "我的设备" card would
   * advertise a port nothing is listening on.
   */
  private fun stopSyncServer() {
    discoveryJob?.cancel()
    discoveryJob = null
    pairingCountdownJob?.cancel()
    pairingCountdownJob = null
    container.syncController.stop()
    _state.update {
      it.copy(
        pairingCode = "",
        pairingCodeSecondsLeft = 0,
        discoveredPeers = emptyList(),
        discovering = false,
        localAddress = "",
      )
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
    // The back gesture is the other way off the sync screen, so it has to tear the server down
    // for the same reason [navigate] does.
    if (current == Screen.Sync) stopSyncServer()
    _state.update {
      it.copy(
        screen = target,
        room = null,
        variants = emptyList(),
        selectedVariant = null,
        danmaku = if (current == Screen.Player) emptyList() else it.danmaku,
        // Leaving the room ends the socket, so the status has to go back to "opening" — otherwise
        // the next room would inherit the previous one's failure banner before its own socket had
        // a chance to say anything.
        danmakuStatus = if (current == Screen.Player) DanmakuStatus.Connecting else it.danmakuStatus,
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

  /**
   * Switches platform from the search screen, staying on it.
   *
   * The browse state is left alone on purpose: the category tree and the room grid belong to the
   * home tab, which is not on screen, and [loadCategories] would fetch a catalogue the user is not
   * looking at. What does have to go is everything derived from the previous platform's search —
   * the results, the cursor and the "searched with" keyword — because those rooms belong to a
   * different platform.
   *
   * The keyword itself survives: it is what the user typed, and re-running it on the new platform
   * is the entire point of putting a switcher here.
   */
  private fun selectSearchPlatform(platform: Platform) {
    if (platform == _state.value.platform) return
    container.preferences.setLastPlatform(platform)
    searchJob?.cancel()
    searchCursor = null
    // The field is the only source that can legitimately hold a keyword here. `lastSearchQuery` is
    // kept as a fallback rather than as the primary: 清空 (`UpdateQuery("")`) clears it together
    // with the results, so a cleared box means "nothing to search" and not "re-run the old one" —
    // `SearchPagingTest` pins that. The fallback only covers the moment between a search
    // completing and its keyword being written back into the field, which some paths do.
    val keyword = _state.value.searchQuery.trim().ifBlank { _state.value.lastSearchQuery.trim() }
    _state.update {
      it.copy(
        platform = platform,
        searchResults = emptyList(),
        searchHasMore = false,
        lastSearchQuery = "",
        loading = false,
        loadingMore = false,
        refreshing = false,
        error = null,
        // Put the keyword back into the field, not just into the search call. `submitSearch` reads
        // `searchQuery`, so a switch made after the field was cleared would otherwise re-run
        // *nothing* and pop "请输入要搜索的主播或直播间名称" — a scolding for tapping a chip. Writing
        // it back also keeps the box and the results describing the same search, which is the
        // invariant this screen has broken before.
        searchQuery = if (keyword.isNotEmpty()) keyword else it.searchQuery,
      )
    }
    // A switch with nothing to search is not an error: it lands on the search screen with an empty
    // state, which is exactly where the user was.
    if (keyword.isNotEmpty()) submitSearch()
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

  /**
   * Re-fetches whatever the current screen shows.
   *
   * [UiAction.Refresh] is emitted by three different pull-to-refresh surfaces and by the home
   * screen's refresh button, and it used to do one fixed thing: reload the room grid. On the follow
   * screen that meant a request for a list that was not even on screen, plus a second `refreshing`
   * writer — the grid finished first and cleared the indicator while the follow refresh was still
   * working through the list.
   */
  private fun refresh() {
    val current = _state.value
    if (current.screen == Screen.Follow) {
      refreshFollowStatus(pullToRefresh = true)
      return
    }
    val category = activeCategory(current)
    if (category == null) {
      loadCategories(current.platform)
      return
    }
    nextCursor = null
    loadRooms(current.platform, category, reset = true, pullToRefresh = current.screen == Screen.Home)
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

  private fun toggleFollow(streamer: Streamer) {
    val engine = container.syncEngine
    val store = container.followStore
    val deviceId = container.preferences.deviceId
    val now = System.currentTimeMillis()
    val hlc = engine.nextHlc()
    store.toggle(streamer, hlc, deviceId, now)
    val followed = store.followed()
    _state.update {
      it.copy(
        followed = followed,
        // Unfollowing drops the room from the avatar row too, so the pair has to be read together.
        specialFollowKeys = store.specialKeys(),
        message = if (followed.any { f -> f.key == streamer.key }) "已关注 ${streamer.streamerName}" else "已取消关注",
      )
    }
  }

  /**
   * Pins or unpins [streamer] on the 特别关注 row.
   *
   * A room that is not followed is refused with a message rather than written: the flag lives on
   * the follow record, so there is nowhere to put it, and a tap that silently does nothing reads
   * as a broken button.
   */
  private fun toggleSpecialFollow(streamer: Streamer) {
    val wasSpecial = streamer.key in _state.value.specialFollowKeys
    val ok = container.followStore.toggleSpecial(
      streamer = streamer,
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = System.currentTimeMillis(),
    )
    if (!ok) {
      _state.update { it.copy(message = "先关注 ${streamer.streamerName}，才能设为特别关注") }
      return
    }
    _state.update {
      it.copy(
        specialFollowKeys = container.followStore.specialKeys(),
        message = if (wasSpecial) "已取消特别关注" else "已设为特别关注",
      )
    }
  }

  private fun refreshFollowStatus(pullToRefresh: Boolean = false) {
    val followed = container.followStore.followed()
    if (followed.isEmpty()) {
      followsJob?.cancel()
      _state.update { it.copy(followed = emptyList(), refreshing = false) }
      return
    }

    // Visiting the tab is not a request for a fresh answer every time.
    //
    // Every navigation to 关注 starts this — including the one that happens when the user taps the
    // tab they are already on — so flicking between two tabs used to queue a full round of live
    // checks per tap. A pull-to-refresh always runs; a plain visit is skipped while the previous
    // answer is still recent.
    val now = System.currentTimeMillis()
    if (!pullToRefresh && now - lastFollowRefreshAt < FOLLOW_REFRESH_MIN_INTERVAL_MILLIS) return
    lastFollowRefreshAt = now

    // One refresh at a time.
    //
    // Two in flight each held the list they read *before* they started, so whichever finished last
    // overwrote the other — sometimes with older live states than the ones already on screen.
    followsJob?.cancel()
    val token = ++followsRefreshToken
    followsJob = viewModelScope.launch {
      if (pullToRefresh) _state.update { it.copy(refreshing = true) }
      try {
        // Independent requests, so they run together — six at a time.
        //
        // This used to be a serial `map`: forty follows meant forty round trips end to end, and a
        // hundred took long enough that the list looked frozen. Nothing downstream depends on the
        // order, because each answer is merged into the row it belongs to as it arrives — so the
        // rows light up as the answers come back instead of all at once at the end.
        val permits = Semaphore(FOLLOW_REFRESH_CONCURRENCY)
        coroutineScope {
          followed.forEach { streamer ->
            launch {
              permits.withPermit {
                val status = try {
                  container.platformRepository.liveStatus(streamer.platform, streamer.roomId)
                } catch (cancelled: CancellationException) {
                  // Re-thrown rather than swallowed: `runCatching` used to catch this, so a
                  // cancelled refresh kept walking the whole list before writing its stale result.
                  throw cancelled
                } catch (error: Exception) {
                  // One room failing leaves its row as it was; a platform rate limiting us is
                  // normal and is not worth a message.
                  null
                }
                if (status == null) return@withPermit
                if (token != followsRefreshToken) return@withPermit

                val updated = streamer.copy(
                  isLive = status.isLive,
                  title = status.title ?: streamer.title,
                  viewerCount = status.viewerCount,
                  coverUrl = status.coverUrl ?: streamer.coverUrl,
                  updatedAt = System.currentTimeMillis(),
                )
                // Merged by key into whatever the list looks like *now*: a follow or an unfollow
                // made while the requests were in flight must not be undone by this snapshot.
                _state.update { current ->
                  current.copy(
                    followed = current.followed.map { room ->
                      if (room.key == updated.key) updated else room
                    },
                  )
                }
              }
            }
          }
        }
      } finally {
        // Only the newest refresh owns the flag; the previous one may still be unwinding.
        if (token == followsRefreshToken) _state.update { it.copy(refreshing = false) }
      }
    }
  }

  // --------------------------------------------------------------------- player

  private fun openRoom(streamer: Streamer) {
    playbackJob?.cancel()
    // Remember where the room was opened from so closing it returns there.
    //
    // Only for a *new* room. The side 刷新 button and the error state's 重试 both call this to
    // re-resolve the room that is already open — at that moment `screen` is already `Player` — and
    // the old fallback wrote `Home` in that case. Retrying a stream opened from the follow list and
    // then closing it dropped the user on the browse tab.
    _state.value.screen.takeIf { it != Screen.Player }?.let { playerReturnScreen = it }
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
        // And the same for the danmaku socket: opening a room starts a new subscription, so the
        // previous room's failure must not be shown while the new one is still connecting.
        danmakuStatus = DanmakuStatus.Connecting,
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
        danmakuStatus = DanmakuStatus.Connecting,
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
    container.followStore.reorder(
      roomKeys = keys,
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = System.currentTimeMillis(),
    )
    _state.update { it.copy(followed = container.followStore.followed()) }
  }

  /**
   * Subscribes to the platform danmaku stream for [streamer].
   *
   * Messages are capped so a very busy room cannot grow the state without bound; the
   * overlay only needs the most recent window to animate.
   *
   * Every event is handled. The previous version matched [DanmakuEvent.Message] and sent
   * everything else to `else -> Unit`, so a socket that never connected produced the same UI as a
   * room where nobody was typing: the panel sat on "正在连接弹幕…" forever, with the reason (a
   * handshake rejection, a 403, an unsupported platform) dropped on the floor. All four adapters
   * already emit [DanmakuEvent.Failed] and [DanmakuEvent.Disconnected]; nothing consumed them.
   *
   * `runCatching` is gone too. It swallowed `CancellationException`, so cancelling this job — which
   * happens on every room close — was treated as a failure and the collect could outlive the
   * screen it belonged to.
   */
  private fun startDanmaku(streamer: Streamer) {
    danmakuJob?.cancel()
    danmakuJob = viewModelScope.launch {
      _state.update { it.copy(danmakuStatus = DanmakuStatus.Connecting) }
      try {
        container.platformGateway.danmaku(streamer.platform, streamer.roomId).collect { event ->
          when (event) {
            is com.livehub.platform.api.DanmakuEvent.Message -> _state.update { current ->
              val next = (current.danmaku + event.message).takeLast(MAX_DANMAKU_BUFFER)
              current.copy(
                danmaku = next,
                // A message is proof the socket works, whatever the last status said.
                danmakuStatus = DanmakuStatus.Connected,
              )
            }
            com.livehub.platform.api.DanmakuEvent.Connected -> _state.update {
              it.copy(danmakuStatus = DanmakuStatus.Connected)
            }
            is com.livehub.platform.api.DanmakuEvent.Disconnected -> _state.update {
              it.copy(
                danmakuStatus = if (event.willRetry) {
                  DanmakuStatus.Reconnecting(event.reason)
                } else {
                  DanmakuStatus.Failed(event.reason.ifBlank { "弹幕连接已断开" })
                },
              )
            }
            is com.livehub.platform.api.DanmakuEvent.Failed -> _state.update {
              it.copy(
                danmakuStatus = DanmakuStatus.Failed(
                  event.error.message?.ifBlank { null } ?: "弹幕连接失败",
                ),
              )
            }
          }
        }
        // The flow completing means the adapter gave up for good; a retry loop would not have
        // returned. Saying so beats a spinner that implies "still trying".
        _state.update {
          if (it.danmakuStatus is DanmakuStatus.Failed) {
            it
          } else {
            it.copy(danmakuStatus = DanmakuStatus.Failed("弹幕连接已结束"))
          }
        }
      } catch (cancelled: CancellationException) {
        // Leaving the room is not a failure: the panel is gone with the screen.
        throw cancelled
      } catch (error: Exception) {
        _state.update {
          it.copy(danmakuStatus = DanmakuStatus.Failed(error.message ?: "弹幕连接失败"))
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
    _state.update { it.copy(themeMode = mode) }
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
          followed = container.followStore.followed(),
          specialFollowKeys = container.followStore.specialKeys(),
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
          followed = container.followStore.followed(),
          specialFollowKeys = container.followStore.specialKeys(),
          syncLog = container.syncEngine.logEntries(),
          message = summary.message,
        )
      }
    }
  }

  /**
   * Tears down everything the process should not keep after the UI is gone.
   *
   * Reached only through `ViewModelStore.clear()`, which is why `MainActivity` now resolves this
   * ViewModel through a `ViewModelProvider` instead of calling the constructor: an instance built
   * inline is never stored, so nothing ever called this, and the LAN server outlived every
   * screen. See [MainActivity]'s field comment.
   */
  override fun onCleared() {
    danmakuJob?.cancel()
    playbackJob?.cancel()
    pairingCountdownJob?.cancel()
    discoveryJob?.cancel()
    container.syncController.stop()
  }

  private companion object {
    const val MAX_DANMAKU_BUFFER = 200
    const val DISCOVERY_POLLS = 15
    const val DISCOVERY_POLL_INTERVAL_MILLIS = 2_000L
    const val LOGIN_POLL_ATTEMPTS = 100
    const val LOGIN_POLL_INTERVAL_MILLIS = 2_000L

    /**
     * How many live checks the follow refresh keeps in flight.
     *
     * Six rather than "all of them": the list can be hundreds long, and every one of these is a
     * request to the same platform API — the point is to stop paying the *sum* of the round trips,
     * not to open a hundred sockets.
     */
    const val FOLLOW_REFRESH_CONCURRENCY = 6

    /**
     * How long a plain visit to 关注 trusts the previous live check.
     *
     * A pull-to-refresh ignores this. Thirty seconds is long enough to cover tab flicking and short
     * enough that a user who leaves the app and comes back still sees a fresh list.
     */
    const val FOLLOW_REFRESH_MIN_INTERVAL_MILLIS = 30_000L
  }
}
