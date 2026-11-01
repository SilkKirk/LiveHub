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
import com.livehub.core.sync.SyncScope
import com.livehub.platform.api.PlatformException
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
  private var searchJob: Job? = null
  private var playbackJob: Job? = null
  private var danmakuJob: Job? = null
  private var nextCursor: String? = null
  private var searchCursor: String? = null

  init {
    val preferences = container.preferences
    val settings = preferences.settings
    val platform = Platform.fromId(settings.lastPlatform) ?: Platform.DOUYU
    _state.update {
      it.copy(
        platform = platform,
        danmakuEnabled = settings.danmakuEnabled,
        blockedKeywords = settings.blockedKeywords,
        syncScopes = settings.syncScopes
          .mapNotNull(SyncScope::fromWireName)
          .toSet()
          .ifEmpty { SyncScope.default },
        deviceName = preferences.deviceName,
        searchHistory = settings.searchHistory,
        followed = container.followStore.followed(),
        peers = preferences.trustedPeers(),
        syncLog = container.syncEngine.logEntries(),
        bilibiliLogin = com.livehub.app.ui.components.BiliLoginUi(
          loggedIn = container.bilibiliAuth.isLoggedIn(),
        ),
      )
    }
    loadCategories(platform)
  }

  fun onAction(action: UiAction) {
    when (action) {
      is UiAction.SelectPlatform -> selectPlatform(action.platform)
      is UiAction.SelectCategory -> selectCategory(action.category)
      is UiAction.ToggleCategorySubscription -> toggleCategorySubscription(action.category)
      is UiAction.MoveFollow -> moveFollow(action.streamer, action.toIndex)
      UiAction.LoadMore -> loadMore()
      UiAction.Refresh -> refresh()
      is UiAction.OpenRoom -> openRoom(action.streamer)
      UiAction.ClosePlayer -> closePlayer()
      is UiAction.SelectVariant -> _state.update { it.copy(selectedVariant = action.variant) }
      is UiAction.ToggleFollow -> toggleFollow(action.streamer)
      is UiAction.UpdateQuery -> _state.update { it.copy(searchQuery = action.query) }
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
      is UiAction.SetBlockedKeywords -> setBlockedKeywords(action.keywords)
      is UiAction.SetSyncScopes -> setSyncScopes(action.scopes)
      UiAction.RefreshPeers -> refreshPeers()
      is UiAction.UnpairPeer -> unpair(action.deviceId)
      is UiAction.PairPeer -> pair(action.peerName, action.address, action.code)
      is UiAction.PairDiscovered -> pair(
        peerName = action.peer.deviceName,
        address = "${action.peer.host}:${action.peer.port}",
        code = action.code,
      )
      UiAction.StartDiscovery -> startDiscovery()
      UiAction.SyncNow -> syncNow()
      UiAction.ToggleTheme -> _state.update { it.copy(darkTheme = !it.darkTheme) }
      UiAction.StartBilibiliLogin -> startBilibiliLogin()
      UiAction.CancelBilibiliLogin -> cancelBilibiliLogin()
      UiAction.LogoutBilibili -> logoutBilibili()
      UiAction.DismissMessage -> _state.update { it.copy(message = null, error = null) }
    }
  }

  // ------------------------------------------------------------------ navigation

  private fun navigate(screen: Screen) {
    _state.update { it.copy(screen = screen) }
    when (screen) {
      Screen.Follow -> refreshFollowStatus()
      Screen.Sync -> {
        container.syncController.start()
        refreshPeers()
        startDiscovery()
        _state.update {
          it.copy(
            localAddress = container.syncController.serverAddress.orEmpty(),
        discoveredPeers = container.syncController.discoveredPeers(),
            pairingCode = container.syncController.beginPairing(),
          )
        }
      }
      else -> Unit
    }
  }

  private fun back() {
    val current = _state.value.screen
    val target = when (current) {
      Screen.Home -> return
      Screen.Player -> Screen.Home
      else -> Screen.Home
    }
    _state.update { it.copy(screen = target, room = null, variants = emptyList(), selectedVariant = null) }
  }

  // ------------------------------------------------------------------- platform

  private fun selectPlatform(platform: Platform) {
    if (platform == _state.value.platform) return
    container.preferences.setLastPlatform(platform)
    _state.update {
      it.copy(
        platform = platform,
        categories = emptyList(),
        selectedCategory = null,
        rooms = emptyList(),
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
        val first = categories.firstOrNull()
        _state.update {
          it.copy(
            categories = categories,
            subscribedCategories = container.categoryStore.subscribed(),
            selectedCategory = first,
          )
        }
        if (first != null) {
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

  private fun selectCategory(category: Category) {
    _state.update { it.copy(selectedCategory = category, rooms = emptyList()) }
    nextCursor = null
    loadRooms(_state.value.platform, category, reset = true)
  }

  private fun loadRooms(platform: Platform, category: Category, reset: Boolean) {
    roomsJob?.cancel()
    roomsJob = viewModelScope.launch {
      _state.update {
        if (reset) it.copy(loading = true, error = null) else it.copy(loadingMore = true)
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
            hasNextPage = page.hasMore,
          )
        }
      } catch (error: Exception) {
        _state.update {
          it.copy(loading = false, loadingMore = false, error = error.message ?: "加载直播列表失败")
        }
      }
    }
  }

  private fun loadMore() {
    val current = _state.value
    val category = current.selectedCategory ?: return
    if (current.loading || current.loadingMore || nextCursor == null) return
    loadRooms(current.platform, category, reset = false)
  }

  private fun refresh() {
    val current = _state.value
    val category = current.selectedCategory
    if (category == null) {
      loadCategories(current.platform)
    } else {
      nextCursor = null
      loadRooms(current.platform, category, reset = true)
    }
    if (current.screen == Screen.Follow) refreshFollowStatus()
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
      _state.update {
        it.copy(
          screen = Screen.Search,
          loading = true,
          loadingMore = false,
          searchResults = emptyList(),
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
          )
        }
      } catch (error: Exception) {
        _state.update { it.copy(loading = false, error = error.message ?: "搜索失败") }
      }
    }
  }

  /** Appends the next page of search results, if the platform reported one. */
  private fun loadMoreSearch() {
    val current = _state.value
    val query = current.searchQuery.trim()
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
        message = if (followed.any { f -> f.key == streamer.key }) "已关注 ${streamer.streamerName}" else "已取消关注",
      )
    }
  }

  private fun refreshFollowStatus() {
    val followed = container.followStore.followed()
    if (followed.isEmpty()) {
      _state.update { it.copy(followed = emptyList()) }
      return
    }
    viewModelScope.launch {
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
      _state.update { it.copy(followed = refreshed) }
    }
  }

  // --------------------------------------------------------------------- player

  private fun openRoom(streamer: Streamer) {
    playbackJob?.cancel()
    _state.update {
      it.copy(screen = Screen.Player, room = streamer, variants = emptyList(), selectedVariant = null, loading = true)
    }
    playbackJob = viewModelScope.launch {
      try {
        val source = container.platformRepository.playback(streamer.platform, streamer.roomId)
        val preferred = source.defaultVariant
        _state.update {
          it.copy(
            variants = source.variants,
            selectedVariant = preferred,
            room = source.room.takeIf { room -> room.streamerName.isNotBlank() } ?: streamer,
            loading = false,
          )
        }
        startDanmaku(streamer)
      } catch (error: Exception) {
        _state.update {
          it.copy(loading = false, error = error.message ?: "无法播放该直播间")
        }
      }
    }
  }

  private fun closePlayer() {
    playbackJob?.cancel()
    danmakuJob?.cancel()
    _state.update {
      it.copy(
        screen = Screen.Home,
        room = null,
        variants = emptyList(),
        selectedVariant = null,
        danmaku = emptyList(),
      )
    }
  }

  private fun toggleCategorySubscription(category: Category) {
    container.categoryStore.toggle(
      category = category,
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = System.currentTimeMillis(),
    )
    val subscribed = container.categoryStore.subscribed()
    _state.update {
      it.copy(
        subscribedCategories = subscribed,
        message = if (subscribed.any { c -> c.key == category.key }) {
          "已订阅 ${category.name}"
        } else {
          "已取消订阅 ${category.name}"
        },
      )
    }
  }

  /**
   * Moves a followed streamer to [toIndex].
   *
   * Every record in the new order is re-stamped with a fresh HLC so the new order wins
   * over any concurrent reorder from another device; without that, two devices sorting
   * differently could ping-pong forever.
   */
  private fun moveFollow(streamer: Streamer, toIndex: Int) {
    val ordered = _state.value.followed
    if (ordered.isEmpty()) return
    val newOrder = com.livehub.app.di.FollowOrdering.move(ordered, streamer, toIndex)
    container.followStore.reorder(
      keys = newOrder,
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

  private fun selectVariant(variant: StreamVariant) {
    _state.update { it.copy(selectedVariant = variant) }
  }

  // ---------------------------------------------------------------- preferences

  private fun setDanmakuEnabled(enabled: Boolean) {
    container.preferences.setDanmakuEnabled(enabled)
    _state.update { it.copy(danmakuEnabled = enabled) }
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
    container.bilibiliAuth.logout()
    _state.update {
      it.copy(
        bilibiliLogin = it.bilibiliLogin.copy(loggedIn = false, status = "已退出登录"),
        message = "已退出哔哩哔哩账号",
      )
    }
  }
  // ----------------------------------------------------------------------- sync

  private fun setSyncScopes(scopes: Set<SyncScope>) {
    container.preferences.setSyncScopes(scopes)
    _state.update { it.copy(syncScopes = scopes) }
  }

  private fun refreshPeers() {
    _state.update {
      it.copy(
        peers = container.preferences.trustedPeers(),
        syncLog = container.syncEngine.logEntries(),
        localAddress = container.syncController.serverAddress.orEmpty(),
        discoveredPeers = container.syncController.discoveredPeers(),
      )
    }
  }

  private fun unpair(deviceId: Long) {
    container.syncEngine.unpair(deviceId)
    refreshPeers()
    _state.update { it.copy(message = "已解除配对") }
  }

  /**
   * Performs the real pairing handshake with the device at [address].
   *
   * The peer validates [code] against the code it is hosting and answers with the
   * shared secret used by every later sync request.
   */
  private fun pair(peerName: String, address: String, code: String) {
    viewModelScope.launch {
      _state.update { it.copy(syncing = true) }
      val result = runCatching { container.syncController.pair(peerName, address, code) }
        .getOrElse { Result.failure(it) }
      _state.update {
        it.copy(
          syncing = false,
          peers = container.preferences.trustedPeers(),
          message = result.fold(
            onSuccess = { name -> "已与 $name 配对" },
            onFailure = { error -> error.message ?: "配对失败" },
          ),
        )
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
          syncLog = container.syncEngine.logEntries(),
          message = summary.message,
        )
      }
    }
  }

  /** Convenience for the UI: the currently selected category's display name. */
  fun categoryTitle(category: Category?): String = category?.name.orEmpty()

  override fun onCleared() {
    super.onCleared()
    danmakuJob?.cancel()
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
