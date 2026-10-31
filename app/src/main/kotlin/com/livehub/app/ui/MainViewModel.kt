package com.livehub.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livehub.app.di.AppContainer
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
      )
    }
    loadCategories(platform)
  }

  fun onAction(action: UiAction) {
    when (action) {
      is UiAction.SelectPlatform -> selectPlatform(action.platform)
      is UiAction.SelectCategory -> selectCategory(action.category)
      UiAction.LoadMore -> loadMore()
      UiAction.Refresh -> refresh()
      is UiAction.OpenRoom -> openRoom(action.streamer)
      UiAction.ClosePlayer -> closePlayer()
      is UiAction.SelectVariant -> _state.update { it.copy(selectedVariant = action.variant) }
      is UiAction.ToggleFollow -> toggleFollow(action.streamer)
      is UiAction.UpdateQuery -> _state.update { it.copy(searchQuery = action.query) }
      UiAction.SubmitSearch -> submitSearch()
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
      is UiAction.PairPeer -> pair(action.peerName, action.code)
      UiAction.SyncNow -> syncNow()
      UiAction.ToggleTheme -> _state.update { it.copy(darkTheme = !it.darkTheme) }
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
        _state.update {
          it.copy(
            localAddress = container.syncController.serverAddress.orEmpty(),
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
        _state.update { it.copy(categories = categories, selectedCategory = first) }
        if (first != null) {
          loadRooms(platform, first, reset = true)
        } else {
          _state.update { it.copy(loading = false) }
        }
      } catch (error: PlatformException) {
        _state.update { it.copy(loading = false, error = error.message) }
      } catch (error: Exception) {
        _state.update { it.copy(loading = false, error = error.message ?: "加载分区失败") }
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
        )
        nextCursor = page.nextCursor
        _state.update {
          val merged = if (reset) page.rooms else it.rooms + page.rooms
          it.copy(
            rooms = merged.distinctBy { room -> room.key },
            loading = false,
            loadingMore = false,
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
    if (query.isEmpty()) return
    container.preferences.addSearchHistory(query)
    searchJob?.cancel()
    searchJob = viewModelScope.launch {
      _state.update {
        it.copy(
          screen = Screen.Search,
          loading = true,
          searchResults = emptyList(),
          error = null,
          searchHistory = container.preferences.settings.searchHistory,
        )
      }
      try {
        val page = container.platformRepository.search(_state.value.platform, query)
        searchCursor = page.nextCursor
        _state.update { it.copy(searchResults = page.rooms, loading = false) }
      } catch (error: Exception) {
        _state.update { it.copy(loading = false, error = error.message ?: "搜索失败") }
      }
    }
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
      )
    }
  }

  private fun unpair(deviceId: Long) {
    container.syncEngine.unpair(deviceId)
    refreshPeers()
    _state.update { it.copy(message = "已解除配对") }
  }

  private fun pair(peerName: String, code: String) {
    viewModelScope.launch {
      val expected = _state.value.pairingCode
      val result = container.syncEngine.completePairing(
        peerDeviceId = (peerName.hashCode().toLong() and 0x7FFFFFFF).coerceAtLeast(1L),
        peerDeviceName = peerName,
        code = code,
        expectedCode = if (expected.isBlank()) code else expected,
      )
      refreshPeers()
      _state.update {
        it.copy(
          message = result.fold(
            onSuccess = { peer -> "已与 ${peer.deviceName} 配对" },
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
  }
}
