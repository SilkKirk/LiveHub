package com.livehub.app.ui

import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.RoomPage
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import com.livehub.core.sync.SyncLogEntry
import com.livehub.core.sync.TrustedPeer

/** Top level destinations of the single-activity app. */
enum class Screen { Home, Category, Search, Follow, Sync, Settings, Player }

/** Everything the UI renders; produced by [MainViewModel]. */
data class UiState(
  val screen: Screen = Screen.Home,
  val platform: Platform = Platform.DOUYU,
  val categories: List<Category> = emptyList(),
  val selectedCategory: Category? = null,
  /**
   * Third level of browsing ("三级分类"), empty for platforms that do not have one.
   * Douyu is the only platform that publishes it.
   */
  val subCategories: List<Category> = emptyList(),
  val selectedSubCategory: Category? = null,
  val rooms: List<Streamer> = emptyList(),
  val followed: List<Streamer> = emptyList(),
  /**
   * Group ids per followed-room key (`douyu:288016`).
   *
   * A room can sit in any number of groups, which is why this is a set rather than a field on
   * [followed]: two devices adding the same room to different groups have to both survive the
   * merge. See [com.livehub.app.di.LibraryStore].
   */
  val roomGroups: Map<String, Set<String>> = emptyMap(),
  /** The user's groups, in their order. */
  val followGroups: List<com.livehub.core.model.FollowGroup> = emptyList(),
  /**
   * Room keys per group, in the order they were arranged **inside** that group.
   *
   * Separate from [followed] because the two orders are independent: the global list is one
   * arrangement, each group has its own. Reading the group order from [followed] would make
   * "arrange rooms inside 游戏" impossible — the group would always show the global order.
   */
  val groupRoomOrder: Map<String, List<String>> = emptyMap(),
  /**
   * Which group the follow screen is scoped to, or null for "all".
   *
   * Kept in the state rather than in a `remember` so returning from a room does not silently
   * drop the user back to the unfiltered list.
   */
  val selectedFollowGroupId: String? = null,
  /** Categories pinned under the platform tab, for the platform of [platform] only. */
  val favorites: List<Category> = emptyList(),
  val searchQuery: String = "",
  /**
   * The keyword [searchResults] were searched with, and the one the next page is requested for.
   *
   * Kept apart from [searchQuery], which is the live text field: the field changes on every
   * keystroke, while a search only runs when the user submits one. Reading the field where the
   * *searched* keyword is meant made the empty state claim "没有找到「英」" as soon as the first
   * character was typed, and made 加载更多 request the next cursor's page for a keyword nobody had
   * searched.
   */
  val lastSearchQuery: String = "",
  val searchResults: List<Streamer> = emptyList(),
  val searchHistory: List<String> = emptyList(),
  /** Cursor for the next page of search results, null when exhausted. */
  val searchHasMore: Boolean = false,
  val room: Streamer? = null,
  val variants: List<StreamVariant> = emptyList(),
  val selectedVariant: StreamVariant? = null,
  /**
   * Every CDN/line the platform advertises for the open room.
   *
   * Douyu resolves one URL per *quality* against a single line, so the line is a separate
   * axis from the variant; the player offers it as its own row.
   */
  val lines: List<String> = emptyList(),
  val selectedLine: String? = null,
  /** True while a quality or line change is being re-resolved. */
  val switchingQuality: Boolean = false,
  val danmakuEnabled: Boolean = true,
  val danmaku: List<com.livehub.core.model.DanmakuMessage> = emptyList(),
  val danmakuOpacity: Float = 1f,
  val danmakuFontScale: Float = 1f,
  val danmakuAreaFraction: Float = 0.5f,
  /** Separate scale for the full-screen overlay, which is much wider than portrait. */
  val landscapeDanmakuFontScale: Float = 1.15f,
  val blockedKeywords: List<String> = emptyList(),
  val peers: List<TrustedPeer> = emptyList(),
  val discoveredPeers: List<com.livehub.core.sync.DiscoveredPeer> = emptyList(),
  val syncLog: List<SyncLogEntry> = emptyList(),
  val deviceName: String = "",
  val localAddress: String = "",
  /** The matching code this device is showing, empty when none. */
  val pairingCode: String = "",
  /** Seconds until [pairingCode] stops working; drives the countdown in the UI. */
  val pairingCodeSecondsLeft: Long = 0,
  /** True while a `PairWithCode` sweep is in flight. */
  val pairing: Boolean = false,
  val syncing: Boolean = false,
  val discovering: Boolean = false,
  val loading: Boolean = false,
  val loadingMore: Boolean = false,
  /**
   * True only while a pull-to-refresh fetch is in flight.
   *
   * Kept separate from [loading] because the two render differently: a pull shows the
   * refresh indicator over the existing list, while [loading] replaces an empty grid with
   * the skeleton.
   */
  val refreshing: Boolean = false,
  /** Set by the ViewModel from the platform cursor; drives infinite scroll. */
  val hasNextPage: Boolean = false,
  val error: String? = null,
  /**
   * A playback failure reported by the decoder itself, as opposed to [error].
   *
   * Kept apart from [error] because the two have different lifetimes: a resolve failure is
   * replaced by the next resolve, while this one invalidates the URL the player already
   * holds and only goes away when the stream is resolved again — which is exactly what the
   * 重试 button asks for. It used to be a `remember` inside the player composable, so the
   * retry cleared nothing: reopening the same room kept the remembered banner, and the
   * button looked dead over a stream that had just been re-resolved successfully.
   */
  val playerError: String? = null,
  val message: String? = null,
  val darkTheme: Boolean = false,
  /**
   * The user's theme choice.
   *
   * Kept as the three-state preference rather than as [darkTheme] alone: "follow the
   * system" is a real option that a boolean cannot express, and it is what a fresh
   * install should use.
   */
  val themeMode: com.livehub.core.designsystem.ThemeMode = com.livehub.core.designsystem.ThemeMode.System,
  val bilibiliLogin: com.livehub.app.ui.components.BiliLoginUi = com.livehub.app.ui.components.BiliLoginUi(),
  /**
   * True when a Douyin web session is stored.
   *
   * Keyword search is gated on it: the endpoint refuses anonymous callers with
   * `2483 请先登录，再继续搜索吧`, so the search box has to know whether to offer it.
   */
  val douyinLoggedIn: Boolean = false,
) {
  /** True only when the adapter reported a next page for the current list. */
  val hasMore: Boolean get() = hasNextPage && !loading && rooms.isNotEmpty()
}

/** User intents handled by [MainViewModel.onAction]. */
sealed interface UiAction {
  data class SelectPlatform(val platform: Platform) : UiAction

  data class SelectCategory(val category: Category) : UiAction

  /**
   * Opens a third level category, or widens back to the whole parent when [category] is null.
   *
   * The null case is the 全部 chip that leads the third row; without it the only way out of a
   * third level was to find and re-tap the parent chip, which the row did not mark as selected.
   */
  data class SelectSubCategory(val category: Category?) : UiAction

  /**
   * Replaces the stored order of the follow list.
   *
   * Whole-list rather than "move item X to index N": the list on screen is a filtered, live-first
   * view, so a view index does not address the stored list. `FollowOrdering.moveWithin` resolves
   * the drop position against the full list and the result travels as this one value.
   */
  data class SetFollowOrder(val keys: List<String>) : UiAction

  /** Replaces the order of the rooms inside one group, leaving the global order untouched. */
  data class SetGroupOrder(val groupId: String, val keys: List<String>) : UiAction

  /** Scopes the follow screen to a group, or to everything when null. */
  data class SelectFollowGroup(val groupId: String?) : UiAction

  data class CreateFollowGroup(val name: String) : UiAction

  data class RenameFollowGroup(val groupId: String, val name: String) : UiAction

  data class DeleteFollowGroup(val groupId: String) : UiAction

  /** Replaces the group row order. [keys] are group ids. */
  data class ReorderFollowGroups(val keys: List<String>) : UiAction

  /** Makes [groupIds] the exact set of groups [streamer] is filed into. */
  data class SetRoomGroups(val streamer: Streamer, val groupIds: Set<String>) : UiAction

  /** Pins or unpins [category] under the platform tab. Aggregate entries are refused. */
  data class ToggleFavorite(val category: Category) : UiAction

  /** Replaces the pinned-category order. [keys] are `LibraryKey.Favorite` encoded values. */
  data class SetFavoriteOrder(val keys: List<String>) : UiAction

  /** Shows a one-line explanation. Used where an action is refused for a reason worth stating. */
  data class ShowMessage(val text: String) : UiAction

  data object LoadMore : UiAction

  data object Refresh : UiAction

  data class OpenRoom(val streamer: Streamer) : UiAction

  data object ClosePlayer : UiAction

  data class SelectVariant(val variant: StreamVariant) : UiAction

  /** Picks a quality code (Douyu rate, Bilibili qn) and re-resolves the stream. */
  data class SelectQuality(val quality: Int) : UiAction

  /** Picks a CDN/line identifier and re-resolves the stream. */
  data class SelectLine(val line: String) : UiAction

  /**
   * The player could not decode the URL it was given.
   *
   * Reported by the player rather than kept as local UI state so that the retry path (which
   * the ViewModel owns) can actually clear it; see [UiState.playerError].
   */
  data class PlayerFailed(val message: String) : UiAction

  data class ToggleFollow(val streamer: Streamer) : UiAction

  data class UpdateQuery(val query: String) : UiAction

  data object SubmitSearch : UiAction

  data class UseHistory(val keyword: String) : UiAction

  data object ClearSearchHistory : UiAction

  data object LoadMoreSearch : UiAction

  data object Back : UiAction

  data class Navigate(val screen: Screen) : UiAction

  data class ToggleDanmaku(val enabled: Boolean) : UiAction

  data class SetDanmakuAreaFraction(val fraction: Float) : UiAction

  data class SetDanmakuFontScale(val scale: Float) : UiAction

  data class SetDanmakuOpacity(val opacity: Float) : UiAction

  data class SetLandscapeDanmakuFontScale(val scale: Float) : UiAction

  data class SetBlockedKeywords(val keywords: List<String>) : UiAction

  data object RefreshPeers : UiAction

  data class UnpairPeer(val deviceId: Long) : UiAction

  /**
   * Pairs with the device showing [code].
   *
   * No address: the code is the whole input, and finding the device it belongs to is the
   * app's job — that is what makes this match WeChat's input-method pairing instead of
   * asking the user to read an IP off another screen.
   */
  data class PairWithCode(val code: String) : UiAction

  /** Issues a fresh matching code to show on this device. */
  data object RefreshPairingCode : UiAction

  data object StartDiscovery : UiAction

  data object SyncNow : UiAction

  data object ToggleTheme : UiAction

  /** Sets the theme cycle position explicitly (system → dark → light). */
  data class SetThemeMode(val mode: com.livehub.core.designsystem.ThemeMode) : UiAction

  data object StartBilibiliLogin : UiAction

  data object CancelBilibiliLogin : UiAction

  data object LogoutBilibili : UiAction

  /**
   * Stores the cookie header captured from the Douyin web login sheet.
   *
   * The cookie is the credential, so it travels as the action's payload rather than through a
   * state field the UI could render.
   */
  data class DouyinLoggedIn(val cookie: String) : UiAction

  data object LogoutDouyin : UiAction

  data object DismissMessage : UiAction
}
