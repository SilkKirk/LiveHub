package com.livehub.app.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livehub.app.di.AppContainer
import com.livehub.app.sync.FollowRefreshWorker
import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import com.livehub.core.network.runCatchingCancellable
import com.livehub.platform.api.PlatformException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
 * platform work to the container. Everything asynchronous runs in
 * [viewModelScope]; a new request cancels the previous one for the same slot so rapid
 * tab switches cannot interleave stale results.
 *
 * LAN sync is [sync] ([SyncSurface]): the shell decides which screen is open, the surface decides
 * what the socket, the pairing code and an exchange do while it is.
 */
class MainViewModel(
  private val container: AppContainer,
  /**
   * The platform's API level and whether the local-network permission is granted, forwarded to
   * [sync].
   *
   * They are constructor parameters here because that is the seam the tests build the ViewModel
   * through, and [SyncSurface] documents why the gate itself must not read `Build` internally.
   */
  private val localNetworkSdk: () -> Int = { android.os.Build.VERSION.SDK_INT },
  private val localNetworkGranted: () -> Boolean = { true },
) : ViewModel() {

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  /**
   * The sync surface: the LAN server's lifetime policy, the pairing page, discovery, and the
   * exchange.
   *
   * Split out of this class in round 130 — see [SyncSurface] for where the cut is and why it is
   * there. It writes the same [_state] as everything else here, because there is one screen state
   * and the sync page is a screen.
   */
  private val sync = SyncSurface(
    container = container,
    scope = viewModelScope,
    state = _state,
    localNetworkSdk = localNetworkSdk,
    localNetworkGranted = localNetworkGranted,
  )

  private var roomsJob: Job? = null
  private var subCategoriesJob: Job? = null
  private var searchJob: Job? = null
  private var playbackJob: Job? = null
  private var danmakuJob: Job? = null
  private var followsJob: Job? = null

  /**
   * Counter behind [com.livehub.core.model.DanmakuMessage.seq], reset per room.
   *
   * Only the danmaku collector touches it, and it runs on the main dispatcher, so a plain `Long`
   * is enough. See the collection site for why a positional cursor is not: the buffer it feeds is
   * capped, so `size` stops moving in a busy room.
   */
  private var danmakuSeq: Long = 0L

  private var nextCursor: String? = null

  /**
   * Where the next page of each platform's results starts.
   *
   * A map rather than one cursor: the cross-platform mode runs four searches, and each of them
   * pages on its own. The single-platform mode simply keeps one entry in it.
   */
  private val searchCursors = mutableMapOf<Platform, String?>()

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
        dynamicColor = settings.dynamicColor,
        danmakuEnabled = settings.danmakuEnabled,
        danmakuOpacity = settings.danmakuOpacity,
        danmakuFontScale = settings.danmakuFontScale,
        danmakuAreaFraction = settings.danmakuAreaFraction,
        landscapeDanmakuFontScale = settings.landscapeDanmakuFontScale,
        blockedKeywords = settings.blockedKeywords,
        // Read here rather than in the settings screen: `LiveHubApplication` consults the same
        // preference before it schedules anything, so the page has to show what is actually
        // scheduled instead of a switch that only describes itself.
        backgroundRefreshEnabled = settings.backgroundRefresh,
        refreshOnWifiOnly = settings.refreshOnWifiOnly,
        miniPlayerEnabled = settings.miniPlayer,
        pictureInPictureEnabled = settings.pictureInPicture,
        deviceName = preferences.deviceName,
        searchHistory = settings.searchHistory,
        recentRooms = settings.recentRooms,
        autoSyncEnabled = settings.autoSync,
        followed = container.followStore.followed(),
        specialFollowKeys = container.followStore.specialKeys(),
        followTags = container.followStore.tagsByKey(),
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
      is UiAction.SetRoomTags -> setRoomTags(action.streamer, action.tags)
      is UiAction.SetFollowTagFilter -> _state.update { it.copy(followTagFilter = action.tag) }
      is UiAction.ShowMessage -> _state.update { it.copy(message = action.text) }
      UiAction.LoadMore -> loadMore()
      UiAction.Refresh -> refresh()
      is UiAction.OpenRoom -> openRoom(action.streamer)
      UiAction.ClosePlayer -> closePlayer()
      UiAction.MinimizePlayer -> minimizePlayer()
      UiAction.ExpandPlayer -> expandPlayer()
      is UiAction.SelectVariant -> selectVariant(action.variant)
      is UiAction.SelectQuality -> selectQuality(action.quality, line = null)
      is UiAction.SelectLine -> selectQuality(quality = null, line = action.line)
      is UiAction.PlayerFailed -> _state.update { it.copy(playerError = action.message) }
      is UiAction.SetPlayerPaused -> _state.update { it.copy(playerPaused = action.paused) }
      is UiAction.ToggleFollow -> toggleFollow(action.streamer)
      is UiAction.UpdateQuery -> {
        if (action.query.isBlank()) {
          // Emptying the field ends the search that was on screen. Without this the 清空 button
          // in the "没有找到" state cleared the text but left `lastSearchQuery` behind, so the
          // page kept announcing a result for a keyword no longer in the field. An in-flight
          // search for the erased keyword goes too, and the flags it owns have to be reset by
          // hand — a cancelled job never reaches its own `loading = false`.
          searchJob?.cancel()
          searchCursors.clear()
          _state.update {
            it.copy(
              searchQuery = action.query,
              lastSearchQuery = "",
              searchResults = emptyList(),
              searchHasMore = false,
              searchFailures = emptyMap(),
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
      is UiAction.SetBackgroundRefresh -> setBackgroundRefresh(action.enabled)
      is UiAction.SetRefreshOnWifiOnly -> setRefreshOnWifiOnly(action.wifiOnly)
      is UiAction.SetMiniPlayer -> setMiniPlayer(action.enabled)
      is UiAction.SetPictureInPicture -> setPictureInPicture(action.enabled)
      UiAction.RefreshPeers -> sync.refreshPeers()
      is UiAction.UnpairPeer -> sync.unpair(action.deviceId)
      is UiAction.PairWithCode -> sync.pairWithCode(action.code)
      UiAction.RefreshPairingCode -> sync.refreshPairingCode()
      UiAction.StartDiscovery -> sync.startDiscovery()
      UiAction.SyncNow -> sync.syncNow()
      is UiAction.SetAutoSync -> sync.setAutoSync(action.enabled)
      is UiAction.SetThemeMode -> setThemeMode(action.mode)
      is UiAction.SetDynamicColor -> setDynamicColor(action.enabled)
      UiAction.StartBilibiliLogin -> startBilibiliLogin()
      UiAction.CancelBilibiliLogin -> cancelBilibiliLogin()
      UiAction.LogoutBilibili -> logoutBilibili()
      is UiAction.DouyinLoggedIn -> douyinLoggedIn(action.cookie)
      UiAction.LogoutDouyin -> logoutDouyin()
      // Only the transient notice. `error` is a *load* failure that the screen renders inline
      // (with a retry button), and the two have different lifetimes — see `UiState.error`'s KDoc.
      // Clearing both here was collateral damage: dismissing any snackbar (a follow, a logout, a
      // cache sweep) also erased the error the home screen was showing, so a failed load turned
      // into "现在没有人在直播" the moment an unrelated message timed out.
      UiAction.DismissMessage -> _state.update { it.copy(message = null) }
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
    // Leaving the sync screen releases what the *page* owns — the six digit code and discovery —
    // and leaves the server's own life to the foreground policy. It used to stop the server
    // unconditionally, which is what made two devices able to meet only while both sat on this
    // page. See [SyncSurface.leaveSyncScreen].
    if (current == Screen.Sync && screen != Screen.Sync) sync.leaveSyncScreen()
    _state.update { it.copy(screen = screen) }
    when (screen) {
      Screen.Follow -> refreshFollowStatus()
      Screen.Sync -> sync.enterSyncScreen()
      else -> Unit
    }
  }

  private fun back() {
    val current = _state.value.screen
    // Leaving the player is not the same as closing the room any more: the stream keeps playing in
    // the mini window, so nothing is torn down here. The mini window's × is what stops it.
    if (current == Screen.Player) {
      minimizePlayer()
      return
    }
    if (current == Screen.Home) {
      // On the start screen with a stream in the corner, back means "stop that one" — the same
      // stack order the rest of the app uses. The next back leaves the app.
      if (_state.value.miniPlayer) closePlayer()
      return
    }
    val target = when (current) {
      Screen.Search -> searchReturnScreen.takeIf { it != Screen.Search } ?: Screen.Home
      else -> Screen.Home
    }
    // The back gesture is the other way off the sync screen, so it releases the same things
    // [navigate] does — the page's code and discovery, not necessarily the server.
    if (current == Screen.Sync) sync.leaveSyncScreen()
    _state.update { it.copy(screen = target) }
  }

  /**
   * Steps off the player screen, leaving the stream running in the mini window.
   *
   * Everything the room owns stays: the resolved variant (a re-resolve would cost a round trip and
   * a second of black), the danmaku socket (reconnecting it would drop the backlog and restart the
   * handshake) and the pause flag. Only the screen changes, and `LiveHubRoot` answers that by
   * resizing the player composable instead of disposing it.
   *
   * With 小窗播放 off, "leave the player" means stop watching: this becomes [closePlayer], which is
   * the same teardown the × in the mini window performs. A stream nobody can see, still using the
   * network and the speaker, is exactly what the switch exists to refuse.
   */
  private fun minimizePlayer() {
    if (!_state.value.miniPlayerEnabled) {
      closePlayer()
      return
    }
    val target = playerReturnScreen.takeIf { it != Screen.Player } ?: Screen.Home
    _state.update { it.copy(screen = target) }
  }

  /** Back to the full player, with the stream that is already playing. */
  private fun expandPlayer() {
    if (_state.value.room == null) return
    _state.update { it.copy(screen = Screen.Player) }
  }

  // --------------------------------------------------------------- app lifecycle

  /**
   * The app came to the foreground. Called from `MainActivity.onStart`.
   *
   * The work — re-arming the sync page, opening the server and running the throttled exchange — is
   * [SyncSurface.onAppForeground]'s, because that is what "foreground" means to sync. It stays here
   * because the activity holds a ViewModel and nothing else.
   */
  fun onAppForeground() = sync.onAppForeground()

  /**
   * The app left the foreground. Called from `MainActivity.onStop`.
   *
   * [SyncSurface.onAppBackground] takes the socket and the pairing code down with it.
   */
  fun onAppBackground() = sync.onAppBackground()

  /**
   * What the user answered to the local-network prompt.
   *
   * The activity owns the dialog and this is where its result arrives; what the answer unblocks is
   * [SyncSurface.onLocalNetworkPermissionResult]'s decision to make.
   */
  fun onLocalNetworkPermissionResult(granted: Boolean) =
    sync.onLocalNetworkPermissionResult(granted)

  // ------------------------------------------------------------------- platform

  private fun selectPlatform(platform: Platform) {
    if (platform == _state.value.platform) return
    container.preferences.setLastPlatform(platform)
    // Search results belong to the platform that produced them: leaving them on screen
    // after a switch labelled 斗鱼 rooms with 虎牙's name.
    searchJob?.cancel()
    searchCursors.clear()
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
        searchFailures = emptyMap(),
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
  /**
   * Switches what the search runs against, without leaving the search screen.
   *
   * [platform] null is "all four at once" — the aggregator's own premise, and the reason this
   * screen got a platform switcher at all.
   */
  private fun selectSearchPlatform(platform: Platform?) {
    val current = _state.value
    val alreadySelected =
      if (platform == null) current.searchAllPlatforms
      else !current.searchAllPlatforms && platform == current.platform
    if (alreadySelected) return
    if (platform != null) container.preferences.setLastPlatform(platform)
    searchJob?.cancel()
    searchCursors.clear()
    // The field is the only source that can legitimately hold a keyword here. `lastSearchQuery` is
    // kept as a fallback rather than as the primary: 清空 (`UpdateQuery("")`) clears it together
    // with the results, so a cleared box means "nothing to search" and not "re-run the old one" —
    // `SearchPagingTest` pins that. The fallback only covers the moment between a search
    // completing and its keyword being written back into the field, which some paths do.
    val keyword = _state.value.searchQuery.trim().ifBlank { _state.value.lastSearchQuery.trim() }
    _state.update {
      it.copy(
        // Choosing 全部 leaves the browse platform alone: the home tab is still showing it, and
        // this chip is about where the search looks.
        platform = platform ?: it.platform,
        searchAllPlatforms = platform == null,
        searchResults = emptyList(),
        searchHasMore = false,
        searchFailures = emptyMap(),
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
      } catch (cancelled: CancellationException) {
        // Re-thrown, like the other seven cancel-aware blocks in this file. `loadCategories`
        // cancels the previous `roomsJob` and starts a new one, and the cancelled coroutine resumes
        // *after* the new one has already written `loading = true, error = null` — so a swallowed
        // CancellationException would overwrite the fresh state with `error = "Job was cancelled"`.
        // That string is English, and [HomeScreen] now renders `error` above the grid, so the bug
        // would be visible to the user rather than merely wrong.
        throw cancelled
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
      val children = try {
        container.platformRepository.subCategories(platform, category.id)
      } catch (cancelled: CancellationException) {
        // Re-thrown for consistency with the other thirteen cancel-aware blocks in this file, and
        // because two mechanisms were masking a swallowed cancellation here rather than one:
        // `selectCategory` clears the row synchronously anyway, and the key guard below refuses a
        // write for a category that is no longer selected. Neither of those was written to cover a
        // *cancelled* request, so relying on them is accidental — the cancellation is not a failure
        // and must not be converted into an empty answer that a future change might act on.
        throw cancelled
      } catch (error: Exception) {
        // A platform that cannot answer has no third level to show; the row stays as it was.
        emptyList()
      }
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
      } catch (cancelled: CancellationException) {
        // A new request replaced this one (a category switch, a pull-to-refresh, a platform
        // change). The replacement already owns every flag this block would touch, and it is the
        // one that has to report a failure — see the comment on `loadCategories`.
        throw cancelled
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
    if (_state.value.searchAllPlatforms) {
      searchEverywhere(query)
      return
    }
    searchJob?.cancel()
    searchCursors.clear()
    // Captured once: the cursor belongs to the platform the query was sent to, even if the user
    // switches the chips while the answer is in flight.
    val platform = _state.value.platform
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
        val page = container.platformRepository.search(platform, query)
        searchCursors[platform] = page.nextCursor
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
        searchCursors.clear()
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

  /**
   * Searches all four platforms at once.
   *
   * This is the aggregator's own premise: the same streamer exists on 斗鱼 and 虎牙 under the same
   * nickname, and comparing them used to mean switching chips and retyping the keyword four times.
   *
   * The four requests go out together and their answers are concatenated in [Platform] order, so
   * the list reads as four sections rather than as one interleaved list of unrelated rooms. A
   * platform that fails is **not** a failed search: 抖音 refuses keyword search to anonymous
   * callers, and that has to show up as its own line next to the three that answered
   * ([UiState.searchFailures]) instead of emptying the page.
   *
   * Paging keeps one cursor per platform ([searchCursors]); `searchHasMore` means "at least one
   * platform has another page", and reaching the bottom fetches the next page of each of them —
   * see [loadMoreEverywhere].
   */
  private fun searchEverywhere(query: String) {
    searchJob?.cancel()
    searchCursors.clear()
    searchJob = viewModelScope.launch {
      _state.update { current ->
        // Same rule as the single-platform path: re-running the same keyword keeps the list and
        // turns the indicator, a new keyword replaces it.
        val sameKeyword = current.lastSearchQuery == query
        current.copy(
          screen = Screen.Search,
          lastSearchQuery = query,
          refreshing = sameKeyword && current.searchResults.isNotEmpty(),
          loading = !sameKeyword || current.searchResults.isEmpty(),
          loadingMore = false,
          searchResults = if (sameKeyword) current.searchResults else emptyList(),
          searchHasMore = false,
          searchFailures = emptyMap(),
          error = null,
          searchHistory = container.preferences.settings.searchHistory,
        )
      }
      val answers = coroutineScope {
        Platform.entries
          .associateWith { platform ->
            async {
              try {
                Result.success(container.platformRepository.search(platform, query))
              } catch (cancelled: CancellationException) {
                // Re-thrown rather than recorded as "this platform failed": a search the user
                // replaced must not report its cancellation next to three real answers.
                throw cancelled
              } catch (error: Exception) {
                Result.failure(error)
              }
            }
          }
          .mapValues { (_, answer) -> answer.await() }
      }
      val rooms = answers.values.mapNotNull { it.getOrNull() }.flatMap { page -> page.rooms }
      answers.forEach { (platform, answer) ->
        answer.getOrNull()?.let { page -> searchCursors[platform] = page.nextCursor }
      }
      val failures = answers.mapNotNull { (platform, answer) ->
        answer.exceptionOrNull()?.let { platform to (it.message ?: "搜索失败") }
      }.toMap()
      _state.update {
        it.copy(
          searchResults = rooms,
          searchHasMore = searchCursors.values.any { cursor -> cursor != null },
          searchFailures = failures,
          loading = false,
          refreshing = false,
        )
      }
    }
  }

  /**
   * Appends the next page of search results.
   *
   * One cursor in the single-platform mode, one per platform in the cross-platform one — see
   * [loadMoreEverywhere] for the latter.
   */
  private fun loadMoreSearch() {
    val current = _state.value
    if (current.searchAllPlatforms) {
      loadMoreEverywhere()
      return
    }
    // The keyword that produced the cursor, not what the field says now: editing the field without
    // submitting used to send the *new* text together with the *old* cursor, so the appended page
    // belonged to a search the user never made.
    val query = current.lastSearchQuery.trim()
    val platform = current.platform
    val cursor = searchCursors[platform]
    if (query.isEmpty() || cursor == null || current.loading || current.loadingMore) return

    searchJob = viewModelScope.launch {
      _state.update { it.copy(loadingMore = true) }
      try {
        val page = container.platformRepository.search(platform, query, cursor)
        searchCursors[platform] = page.nextCursor
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

  /**
   * Fetches the next page of every platform that still has one.
   *
   * One trigger from the grid (reaching the bottom) turns into up to four requests, run together.
   * A platform whose page fails loses its cursor and gets a line in [UiState.searchFailures]: a
   * retry on the next scroll would be the same request again, and the user should see why a section
   * stopped growing rather than watch it silently stop.
   */
  private fun loadMoreEverywhere() {
    val current = _state.value
    val query = current.lastSearchQuery.trim()
    // Captured cursors rather than a re-read of the map: the request has to carry the cursor the
    // decision was made on, and a platform with no cursor must not be asked for page one again
    // (that answer would be dropped by `distinctBy` and look like a page that arrived empty).
    val pending = searchCursors.mapNotNull { (platform, cursor) ->
      cursor?.let { platform to it }
    }.toMap()
    if (query.isEmpty() || pending.isEmpty() || current.loading || current.loadingMore) return

    searchJob = viewModelScope.launch {
      _state.update { it.copy(loadingMore = true) }
      val answers = coroutineScope {
        pending
          .mapValues { (platform, cursor) ->
            async {
              try {
                Result.success(container.platformRepository.search(platform, query, cursor))
              } catch (cancelled: CancellationException) {
                throw cancelled
              } catch (error: Exception) {
                Result.failure(error)
              }
            }
          }
          .mapValues { (_, answer) -> answer.await() }
      }
      val added = answers.values.mapNotNull { it.getOrNull() }.flatMap { page -> page.rooms }
      answers.forEach { (platform, answer) ->
        answer.getOrNull()?.let { page -> searchCursors[platform] = page.nextCursor }
        if (answer.isFailure) searchCursors.remove(platform)
      }
      val failures = answers.mapNotNull { (platform, answer) ->
        answer.exceptionOrNull()?.let { platform to (it.message ?: "加载更多失败") }
      }.toMap()
      _state.update {
        it.copy(
          searchResults = (it.searchResults + added).distinctBy { room -> room.key },
          searchHasMore = searchCursors.values.any { cursor -> cursor != null },
          searchFailures = it.searchFailures + failures,
          loadingMore = false,
        )
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
        // And its labels go with the record, so the map has to be re-read rather than edited.
        followTags = store.tagsByKey(),
        message = if (followed.any { f -> f.key == streamer.key }) "已关注 ${streamer.streamerName}" else "已取消关注",
      )
    }
  }

  /**
   * Replaces one room's labels.
   *
   * Written through the store (which stamps the record with a fresh clock, so the change wins the
   * next merge and travels to paired devices). The local view is re-read from the store afterwards
   * instead of being patched, because "which tags exist at all" is derived from every room.
   *
   * The active tag filter is cleared if this was the last room carrying it: otherwise the grid would
   * stay narrowed to a chip that no longer exists.
   */
  private fun setRoomTags(streamer: Streamer, tags: List<String>) {
    val ok = container.followStore.setTags(
      streamer = streamer,
      tags = tags,
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = System.currentTimeMillis(),
    )
    if (!ok) {
      _state.update { it.copy(message = "先关注 ${streamer.streamerName}，才能给它加标签") }
      return
    }
    _state.update { current ->
      val byKey = container.followStore.tagsByKey()
      val ready = current.copy(followTags = byKey)
      val filter = current.followTagFilter
      if (filter != null && byKey.values.none { filter in it }) {
        ready.copy(followTagFilter = null)
      } else {
        ready
      }
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

  /**
   * Puts [streamer] at the front of 最近看过, in the state and on disk.
   *
   * Both halves from one call: the state is what the search screen draws, the record is what
   * survives the process. Writing only the record would leave the row stale until the next launch;
   * writing only the state would lose it on the next launch.
   *
   * The list is read back from [com.livehub.app.di.UserPreferences] instead of being recomputed
   * here because `update` publishes the new record to its cache *before* handing the disk write
   * off, so this is exactly the value that was just stored — and "move to front, dedupe by key,
   * keep 12" stays a single implementation rather than two that can drift.
   */
  private fun rememberRecentRoom(streamer: Streamer) {
    container.preferences.addRecentRoom(streamer)
    val recent = container.preferences.settings.recentRooms
    _state.update { it.copy(recentRooms = recent) }
  }

  private fun openRoom(streamer: Streamer) {
    // Recorded on intent, before anything is resolved.
    //
    // The user pressed play on *this* room, and a room whose stream fails to resolve is still one
    // they were looking for a second later. Recording after a successful resolve would also drop
    // every visit that ended in a network error or a decoder that gave up — which on a bad
    // connection is most of them, i.e. exactly the visits worth finding again.
    rememberRecentRoom(streamer)
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
        // A new room starts playing; a pause left behind by the previous one (or by the app being
        // hidden) would otherwise be inherited by a stream the user just opened.
        playerPaused = false,
        // And the same for the danmaku socket: opening a room starts a new subscription, so the
        // previous room's failure must not be shown while the new one is still connecting.
        danmakuStatus = DanmakuStatus.Connecting,
        // The buffer belongs to the room that produced it. Opening a second room from the mini
        // window never goes through [closePlayer], so without this the new room's player and panel
        // showed the previous room's chat under the new room's name — and since the buffer was
        // already full, the new room's own messages had nowhere to go (see [MAX_DANMAKU_BUFFER]).
        danmaku = emptyList(),
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
      } catch (cancelled: CancellationException) {
        // Opening a second room cancels the first resolve. The new one owns `loading` and `error`;
        // letting the cancelled one write would leave "Job was cancelled" on the screen, and the
        // success branch below deliberately does not clear `error`, so it would survive until the
        // next room.
        throw cancelled
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
      } catch (cancelled: CancellationException) {
        // Switching quality twice cancels the first re-resolve; only the newest one may write
        // `switchingQuality` and report a failure.
        throw cancelled
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
    danmakuSeq = 0L
    // Closing from the mini window leaves the user exactly where they were browsing; only closing
    // the player screen itself navigates back to where the room was opened from.
    val current = _state.value.screen
    val target = if (current == Screen.Player) {
      playerReturnScreen.takeIf { it != Screen.Player } ?: Screen.Home
    } else {
      current
    }
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
        // Nothing is open any more, so nothing can be paused.
        playerPaused = false,
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
    // Each room starts a new numbering: the sequence is an identity within one chat stream, and the
    // buffer it belongs to is cleared whenever the room changes. This runs before the collector is
    // launched, so the first message of the new room is stamped 1 even though the previous room's
    // counter had reached the cap.
    danmakuSeq = 0L
    danmakuJob = viewModelScope.launch {
      _state.update { it.copy(danmakuStatus = DanmakuStatus.Connecting) }
      try {
        container.platformGateway.danmaku(streamer.platform, streamer.roomId).collect { event ->
          when (event) {
            is com.livehub.platform.api.DanmakuEvent.Message -> _state.update { current ->
              // The rolling buffer caps at [MAX_DANMAKU_BUFFER], so position inside it stops being
              // an identity: once it is full, one message leaves the front for every one that
              // arrives and every index shifts. Stamping a per-collector counter here is what lets
              // the overlay tell "new" from "already shown" and lets the list key its rows, in
              // both cases without relying on the buffer's length. See [danmakuSeq].
              val message = event.message.copy(seq = danmakuSeq + 1)
              danmakuSeq = message.seq
              val next = (current.danmaku + message).takeLast(MAX_DANMAKU_BUFFER)
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

  /**
   * Persists and applies the Material You choice.
   *
   * Both halves are needed for the same reason [setThemeMode] documents: the theme is composed from
   * [UiState], so writing the preference alone would change the next launch instead of this one.
   */
  private fun setDynamicColor(enabled: Boolean) {
    container.preferences.setDynamicColor(enabled)
    _state.update { it.copy(dynamicColor = enabled) }
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

  /**
   * Persists the background-refresh switch and makes `WorkManager` agree with it.
   *
   * All three steps matter: the preference is what the worker reads on its next run, the state is
   * what the switch renders, and the schedule/cancel is what stops a job that is already enqueued
   * from waking the process every half hour to do nothing. Writing the preference without
   * cancelling would leave the switch looking off over a job that still runs.
   */
  private fun setBackgroundRefresh(enabled: Boolean) {
    container.preferences.setBackgroundRefresh(enabled)
    _state.update { it.copy(backgroundRefreshEnabled = enabled) }
    applyBackgroundRefreshSchedule(enabled)
  }

  /**
   * Persists the Wi-Fi restriction.
   *
   * No scheduling here on purpose: the worker re-reads this preference on every run, so asking
   * `WorkManager` for a new request would only restart the interval that is already counting down
   * (`ExistingPeriodicWorkPolicy.KEEP` would drop it anyway, which makes the call pure noise).
   */
  private fun setRefreshOnWifiOnly(wifiOnly: Boolean) {
    container.preferences.setRefreshOnWifiOnly(wifiOnly)
    _state.update { it.copy(refreshOnWifiOnly = wifiOnly) }
  }

  /**
   * Persists the mini-window switch.
   *
   * Nothing is started or stopped here: the value is read at the moment the player is left
   * ([minimizePlayer]), so flipping it while the player is open must not close the stream the user
   * is watching.
   */
  private fun setMiniPlayer(enabled: Boolean) {
    container.preferences.setMiniPlayer(enabled)
    _state.update { it.copy(miniPlayerEnabled = enabled) }
  }

  /**
   * Persists the picture-in-picture switch.
   *
   * The state change is the whole effect: `MainActivity` collects the state and re-applies the
   * window's parameters, because `setPictureInPictureParams` is the only way to arm or disarm
   * auto-enter and only an activity can call it.
   */
  private fun setPictureInPicture(enabled: Boolean) {
    container.preferences.setPictureInPicture(enabled)
    _state.update { it.copy(pictureInPictureEnabled = enabled) }
  }

  /**
   * Enqueues or cancels the periodic refresh job.
   *
   * `runCatching` because `WorkManager.getInstance` throws when its initializer never ran — which
   * is the normal state in unit tests and possible in restricted profiles. The switch has already
   * been persisted by the time this runs, so a failure here leaves the preference authoritative
   * (the next launch enqueues from it) instead of leaving the user with a tap that did nothing.
   */
  private fun applyBackgroundRefreshSchedule(enabled: Boolean) {
    val context = container.preferences.appContext
    runCatching {
      if (enabled) {
        FollowRefreshWorker.schedule(context)
      } else {
        FollowRefreshWorker.cancel(context)
      }
    }.onFailure { error ->
      Log.w(BACKGROUND_WORK_TAG, "后台刷新任务启停失败，下次启动时按偏好重新调度", error)
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
        // Off the main thread. `requestQrCode` suspends, but the render does not: ZXing encodes the
        // payload, then the loop below fills 512×512 = 262,144 ints one at a time and allocates a
        // 1 MB bitmap — all inside the frame that answers the tap, on the dispatcher this coroutine
        // runs on. The card shows "正在获取二维码…" until it finishes, so the pause lands exactly
        // when the user is waiting for feedback.
        val bitmap = withContext(Dispatchers.Default) {
          com.livehub.app.ui.qr.QrCodeRenderer.render(qr.url, sizePx = 512)
        }
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
      } catch (cancelled: CancellationException) {
        // The user pressed 取消 while the QR code was being fetched or polled. `cancelBilibiliLogin`
        // already reset the card synchronously; without this the cancelled coroutine would paint
        // "Job was cancelled" — in English — over its own 取消 button.
        throw cancelled
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
      // `runCatchingCancellable`: 取消 means the user pressed 取消 or the screen went away, and it
      // must not be read as "the poll said nothing". The outer coroutine rethrows it, which is what
      // lets `startBilibiliLogin` keep that case out of the error path.
      when (val result = runCatchingCancellable { container.bilibiliAuth.poll(key) }.getOrNull()) {
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
    // The pairing countdown, the discovery poll and the socket are the sync surface's own; see
    // [SyncSurface.shutdown].
    sync.shutdown()
  }

  private companion object {
    const val MAX_DANMAKU_BUFFER = 200

    /** Log tag for the `WorkManager` calls, which fail on platforms where it is unavailable. */
    const val BACKGROUND_WORK_TAG = "MainViewModel"
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
