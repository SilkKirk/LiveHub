package com.livehub.app.ui

import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.RoomPage
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import com.livehub.core.sync.SyncLogEntry
import com.livehub.core.sync.SyncScope
import com.livehub.core.sync.TrustedPeer

/** Top level destinations of the single-activity app. */
enum class Screen { Home, Category, Search, Follow, Sync, Settings, Player }

/** Everything the UI renders; produced by [MainViewModel]. */
data class UiState(
  val screen: Screen = Screen.Home,
  val platform: Platform = Platform.DOUYU,
  val categories: List<Category> = emptyList(),
  val subscribedCategories: List<Category> = emptyList(),
  val selectedCategory: Category? = null,
  val rooms: List<Streamer> = emptyList(),
  val followed: List<Streamer> = emptyList(),
  val searchQuery: String = "",
  val searchResults: List<Streamer> = emptyList(),
  val searchHistory: List<String> = emptyList(),
  val room: Streamer? = null,
  val variants: List<StreamVariant> = emptyList(),
  val selectedVariant: StreamVariant? = null,
  val danmakuEnabled: Boolean = true,
  val danmaku: List<com.livehub.core.model.DanmakuMessage> = emptyList(),
  val danmakuOpacity: Float = 1f,
  val danmakuFontScale: Float = 1f,
  val danmakuAreaFraction: Float = 0.5f,
  val blockedKeywords: List<String> = emptyList(),
  val peers: List<TrustedPeer> = emptyList(),
  val syncLog: List<SyncLogEntry> = emptyList(),
  val syncScopes: Set<SyncScope> = SyncScope.default,
  val deviceName: String = "",
  val localAddress: String = "",
  val pairingCode: String = "",
  val syncing: Boolean = false,
  val loading: Boolean = false,
  val loadingMore: Boolean = false,
  val error: String? = null,
  val message: String? = null,
  val darkTheme: Boolean = false,
) {
  val hasMore: Boolean get() = !loading && rooms.isNotEmpty()
}

/** User intents handled by [MainViewModel.onAction]. */
sealed interface UiAction {
  data class SelectPlatform(val platform: Platform) : UiAction

  data class SelectCategory(val category: Category) : UiAction

  data class ToggleCategorySubscription(val category: Category) : UiAction

  data class MoveFollow(val streamer: Streamer, val toIndex: Int) : UiAction

  data object LoadMore : UiAction

  data object Refresh : UiAction

  data class OpenRoom(val streamer: Streamer) : UiAction

  data object ClosePlayer : UiAction

  data class SelectVariant(val variant: StreamVariant) : UiAction

  data class ToggleFollow(val streamer: Streamer) : UiAction

  data class UpdateQuery(val query: String) : UiAction

  data object SubmitSearch : UiAction

  data class UseHistory(val keyword: String) : UiAction

  data object Back : UiAction

  data class Navigate(val screen: Screen) : UiAction

  data class ToggleDanmaku(val enabled: Boolean) : UiAction

  data class SetBlockedKeywords(val keywords: List<String>) : UiAction

  data class SetSyncScopes(val scopes: Set<SyncScope>) : UiAction

  data object RefreshPeers : UiAction

  data class UnpairPeer(val deviceId: Long) : UiAction

  data class PairPeer(val peerName: String, val code: String) : UiAction

  data object SyncNow : UiAction

  data object ToggleTheme : UiAction

  data object DismissMessage : UiAction
}
