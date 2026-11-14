package com.livehub.app.ui

import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.RoomPage
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import com.livehub.core.sync.SyncLogEntry
import com.livehub.core.sync.TrustedPeer

/**
 * Top level destinations of the single-activity app.
 *
 * There used to be a `Category` entry. Nothing ever set `screen = Screen.Category` — the category
 * tree lives inline on `Home` — so the only reference to it was a `screen == Screen.Category`
 * check that could never be true, and the bottom bar carried a `Dock.HOME` exemption for a state
 * that could not exist. Removed rather than left as a plausible-looking destination.
 */
enum class Screen { Home, Search, Follow, Sync, Settings, Player }

/**
 * The live-state filter on the follow screen.
 *
 * Lives here rather than as a private enum inside `FollowScreen` because the selection is now part
 * of [UiState]: the screen is recomposed from scratch when the player closes, and a filter that
 * only existed in a `remember` was reset every time.
 */
enum class FollowLiveFilter(val label: String) {
  ALL("全部"),
  LIVE("直播中"),
  OFFLINE("未开播"),
  ;

  fun matches(streamer: Streamer): Boolean = when (this) {
    ALL -> true
    LIVE -> streamer.isLive
    OFFLINE -> !streamer.isLive
  }
}

/**
 * What the danmaku socket is doing, so the player can say something other than "正在连接弹幕…".
 *
 * The panel used to have exactly two states — a list with messages, or a spinner — because the
 * ViewModel threw away every [com.livehub.platform.api.DanmakuEvent] except `Message`. A room
 * whose handshake was rejected therefore looked identical to a room that was merely quiet, and the
 * spinner implied it was still trying.
 */
sealed interface DanmakuStatus {
  /** Nothing has come back yet; the socket is being opened. */
  data object Connecting : DanmakuStatus

  /** The adapter reported a live connection, or a message arrived. */
  data object Connected : DanmakuStatus

  /**
   * The connection dropped and the adapter is retrying on its own.
   *
   * Kept apart from [Failed] because the UI should stay quiet here: the list still holds what it
   * had, and a red banner over a stream that reconnects in two seconds is worse than nothing.
   */
  data class Reconnecting(val reason: String) : DanmakuStatus

  /** The adapter gave up. [reason] is what the platform said, and is safe to show. */
  data class Failed(val reason: String) : DanmakuStatus
}

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
   * Keys of the rooms pinned to the 特别关注 avatar row, in the user's order.
   *
   * Kept as keys rather than a second `List<Streamer>`: the row is a view over [followed], so it
   * has to disappear immediately when one of them is unfollowed, and a separate list would need
   * its own refresh path for every follow change.
   */
  val specialFollowKeys: List<String> = emptyList(),
  /**
   * The live-state filter on the follow screen.
   *
   * In the state rather than in a `remember`: opening a room and coming back used to reset it, so
   * a user who had narrowed the list to 直播中 was handed the full list again every time they
   * watched something.
   */
  val followLiveFilter: FollowLiveFilter = FollowLiveFilter.ALL,
  /** The platform filter on the follow screen; null means every platform. */
  val followPlatformFilter: Platform? = null,
  /**
   * The name filter on the follow screen, already trimmed by the caller.
   *
   * In the state rather than in the field's own `remember` for the same reason the two filters
   * above are: opening a room and coming back used to reset everything the screen had selected.
   */
  val followQuery: String = "",
  /**
   * The user's own labels per followed room, keyed by `Streamer.key`.
   *
   * Read from the follow records rather than stored separately (they live on the record, so they
   * sync with it). Kept here because the screen cannot read the store — it is a JVM module below
   * this one.
   */
  val followTags: Map<String, List<String>> = emptyMap(),
  /** The one tag the follow grid is narrowed to, or null for every room. */
  val followTagFilter: String? = null,
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
  /**
   * True when a search runs against all four platforms at once.
   *
   * [platform] keeps meaning "the platform the browse screens are showing"; the search screen used
   * to borrow it as its own selection, which is why "all platforms" had nowhere to live.
   */
  val searchAllPlatforms: Boolean = false,
  /**
   * Why a platform contributed nothing to the last cross-platform search, keyed by platform.
   *
   * Only the all-platforms mode fills this: four requests go out, and one of them failing must read
   * as "抖音 needs a login" next to the three that answered — not as "no results anywhere".
   */
  val searchFailures: Map<Platform, String> = emptyMap(),
  val searchHistory: List<String> = emptyList(),
  /**
   * Rooms the user opened recently, newest first.
   *
   * Local only, and deliberately separate from [followed]: 关注 answers "who do I want to keep up
   * with", this answers "what was I just watching" — the second question is what the search
   * screen's 最近看过 row exists for, since searching for a room you had open a minute ago is the
   * one search this app can answer without going to a platform.
   */
  val recentRooms: List<Streamer> = emptyList(),
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
  /**
   * What the danmaku socket is doing.
   *
   * Separate from [danmaku] because an empty list is ambiguous: it means "nothing said yet" on a
   * healthy connection and "never connected" on a broken one. See [DanmakuStatus].
   */
  val danmakuStatus: DanmakuStatus = DanmakuStatus.Connecting,
  val danmakuOpacity: Float = 1f,
  val danmakuFontScale: Float = 1f,
  val danmakuAreaFraction: Float = 0.5f,
  /** Separate scale for the full-screen overlay, which is much wider than portrait. */
  val landscapeDanmakuFontScale: Float = 1.15f,
  val blockedKeywords: List<String> = emptyList(),
  val peers: List<TrustedPeer> = emptyList(),
  val discoveredPeers: List<com.livehub.core.sync.DiscoveredPeer> = emptyList(),
  /**
   * Whether the LAN server runs while the app is in the foreground, and whether arriving in the
   * foreground syncs once by itself.
   *
   * In the state, not read from preferences by the screen: the switch renders it and the screen is
   * a JVM module below this one. See [UiAction.SetAutoSync].
   */
  val autoSyncEnabled: Boolean = true,
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
  /**
   * True while the open stream is paused.
   *
   * It used to be a `remember` inside the player composable, which made it invisible to everything
   * outside that composable — including the activity, which now pauses the stream when the app is
   * hidden without a picture-in-picture window. [UiAction.SetPlayerPaused] is the only way it
   * changes: the player's double tap, and that exit path.
   */
  val playerPaused: Boolean = false,
  val message: String? = null,
  /**
   * The user's theme choice, and the only thing the theme depends on.
   *
   * Two fields used to describe the theme: this one and a `darkTheme: Boolean`. The boolean was
   * written next to it on every change and read by nothing except two assertions in
   * `MainViewModelTest`, which meant "is it dark right now" could disagree with the actual choice
   * (a `System` mode on a dark system reported `darkTheme = false`). A derived value nobody reads
   * is worse than no value; the tests now assert on [themeMode] itself.
   */
  val themeMode: com.livehub.core.designsystem.ThemeMode = com.livehub.core.designsystem.ThemeMode.System,
  /**
   * Whether the theme should follow the wallpaper's colours (Material You) instead of the brand
   * palette.
   *
   * Mirrors `UserSettings.dynamicColor`. It is in the state because [LiveHubRoot] passes it into
   * `LiveHubTheme` and the settings page renders it — a screen cannot read `UserPreferences`, which
   * lives below it (see [UiAction.SetDynamicColor]).
   */
  val dynamicColor: Boolean = false,
  /**
   * Whether the periodic background refresh of the follow list is switched on.
   *
   * Mirrors `UserSettings.backgroundRefresh`. Default on because that is what the app did before
   * the switch existed — a state that started out false would have the settings page claim the
   * feature is off on a fresh install that is in fact about to schedule it.
   */
  val backgroundRefreshEnabled: Boolean = true,
  /** Whether that refresh is restricted to Wi-Fi networks. */
  val refreshOnWifiOnly: Boolean = false,
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

  /**
   * True while the open room plays in the mini window instead of the player screen.
   *
   * Derived rather than stored: "a room is open" and "the player screen is not on" are both
   * already in this state, and a third field would be a fourth thing to keep consistent. The two
   * actions below are what move it.
   */
  val miniPlayer: Boolean get() = room != null && screen != Screen.Player
}

/** User intents handled by [MainViewModel.onAction]. */
sealed interface UiAction {
  data class SelectPlatform(val platform: Platform) : UiAction

  /**
   * Chooses which platform the search runs against; `null` means all four at once.
   *
   * Distinct from [SelectPlatform], which is the home screen's switcher: that one resets the
   * browse state and navigates to `Screen.Home`, because a platform change invalidates the
   * category tree and the room grid. On the search screen neither exists, so doing that would
   * throw the user back to the home tab and erase the keyword they were searching for — which is
   * exactly why the search page had no platform control at all, and why changing platform meant
   * going back to 首页 first.
   *
   * This one keeps [UiState.searchQuery] and re-runs the search when a keyword has already been
   * submitted, so comparing "the same streamer on 斗鱼 and 虎牙" is two taps — or one, with `null`.
   */
  data class SelectSearchPlatform(val platform: Platform?) : UiAction

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

  /**
   * Pins or unpins [streamer] on the 特别关注 row.
   *
   * Refused (with a message) when the room is not followed: a pin to a row it can never appear in
   * would be a tap that silently does nothing.
   */
  data class ToggleSpecialFollow(val streamer: Streamer) : UiAction

  /** Narrows the follow screen to live / offline rooms. */
  data class SetFollowLiveFilter(val filter: FollowLiveFilter) : UiAction

  /** Narrows the follow screen to one platform, or clears the filter when null. */
  data class SetFollowPlatformFilter(val platform: Platform?) : UiAction

  /**
   * Narrows the follow list to rooms whose streamer name or title contains this text.
   *
   * A filter, not a search: it looks at what has already been followed and never goes to a platform.
   * The field appears once the list is long enough to need one — see
   * `FOLLOW_SEARCH_THRESHOLD` in `FollowScreen`.
   */
  data class SetFollowQuery(val query: String) : UiAction

  /** Replaces the labels on one followed room; an empty list removes them all. */
  data class SetRoomTags(val streamer: Streamer, val tags: List<String>) : UiAction

  /** Narrows the follow grid to one tag, or clears the narrowing when null. */
  data class SetFollowTagFilter(val tag: String?) : UiAction

  /** Shows a one-line explanation. Used where an action is refused for a reason worth stating. */
  data class ShowMessage(val text: String) : UiAction

  data object LoadMore : UiAction

  data object Refresh : UiAction

  data class OpenRoom(val streamer: Streamer) : UiAction

  data object ClosePlayer : UiAction

  /**
   * Leaves the player screen without stopping the stream.
   *
   * The room, its resolved variant and the danmaku socket all stay open; only [UiState.screen]
   * changes, and the composable that draws the stream is resized into the mini window rather than
   * disposed — see `LiveHubRoot`. That is what makes leaving the player free instead of a fresh
   * resolve and a second of black picture.
   *
   * Sent by the back gesture and by the player's left-edge swipe. [ClosePlayer] — the mini window's
   * × — is the one that actually stops.
   */
  data object MinimizePlayer : UiAction

  /** Returns to the full player with the stream that is already playing. */
  data object ExpandPlayer : UiAction

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

  /**
   * Pauses or resumes the stream that is open.
   *
   * Two senders, one state: the player's double tap, and the activity when the app is hidden
   * without a picture-in-picture window. A stream that keeps playing with no window on screen and
   * no media notification is audio the user has no way to stop.
   */
  data class SetPlayerPaused(val paused: Boolean) : UiAction

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

  /**
   * Turns the periodic follow-status refresh on or off.
   *
   * The preference alone is not enough: the worker honours it on every run, but a job that is
   * still enqueued keeps waking the process every 30 minutes to answer "off" and return. The
   * handler therefore also schedules or cancels the work.
   */
  data class SetBackgroundRefresh(val enabled: Boolean) : UiAction

  /**
   * Restricts the periodic refresh to Wi-Fi, or lets it use mobile data.
   *
   * No scheduling here, in contrast to [SetBackgroundRefresh]: the worker re-reads this value on
   * every run, so re-enqueueing would only reset the 30-minute interval already counting down.
   */
  data class SetRefreshOnWifiOnly(val wifiOnly: Boolean) : UiAction

  data object RefreshPeers : UiAction

  /**
   * Turns the always-on LAN server, and the automatic sync on entering the foreground, on or off.
   *
   * Unlike [SetBackgroundRefresh] this needs no scheduling: the server is started and stopped
   * directly from the new value, because the switch decides whether a socket is listening *now*.
   */
  data class SetAutoSync(val enabled: Boolean) : UiAction

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

  /**
   * Switches the palette between the brand colours and the wallpaper's (Material You).
   *
   * The write and the state update travel together: the theme is composed from [UiState], so a
   * preference written without the state update would not be drawn until the next restart.
   */
  data class SetDynamicColor(val enabled: Boolean) : UiAction

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
