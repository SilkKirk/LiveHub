package com.livehub.app.ui.screens

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A live room can be handed to another app.
 *
 * The app had no share path at all — nothing in the source ever sent `ACTION_SEND`, so a room
 * could not be given to anyone who was not already holding the phone. No resolver is involved
 * ([shareUrl] explains why), which makes the whole feature a chooser plus a string: these tests
 * pin down that the string is the platform's own room page, and that pressing 分享 is what puts
 * the chooser in front of the user.
 *
 * The composition is rendered the way [PlayerChromeTest] renders it — stand-in video surface,
 * frozen clock — because the real surface is a decoder and no JVM test can build one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PlayerShareTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val room = Streamer(
    platform = Platform.DOUYU,
    roomId = "1",
    streamerName = "一条小团团",
    title = "今天继续冲分",
    viewerCount = 128_500,
    isLive = true,
  )

  private val variant = StreamVariant(
    qualityLabel = "原画",
    url = "https://example.com/a.flv",
    lineName = "ws-h5",
    qualityRank = 0,
    qualityCode = 0,
  )

  private val application: Application
    get() = ApplicationProvider.getApplicationContext<Application>()

  private fun uiState() = UiState(
    screen = Screen.Player,
    platform = Platform.DOUYU,
    categories = listOf(Category(Platform.DOUYU, "86", "英雄联盟", "1", "网游")),
    room = room,
    variants = listOf(variant),
    selectedVariant = variant,
    lines = listOf("ws-h5", "ali"),
    selectedLine = "ws-h5",
  )

  private fun roomOn(platform: Platform, roomId: String): Streamer =
    room.copy(platform = platform, roomId = roomId)

  /**
   * Renders the player body with a stand-in surface and a frozen clock.
   *
   * The clock is frozen for the reason the chrome tests freeze it: the player hides its own
   * chrome on a timer, and an auto-advancing clock would run that timer — and the danmaku
   * animation — to completion before any assertion could look at the first frame.
   */
  private fun render(fullscreen: Boolean = false, compact: Boolean = false) {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      PlayerContent(
        state = uiState(),
        onAction = {},
        fullscreen = fullscreen,
        onFullscreenChange = {},
        onOpenSettings = {},
        videoSurface = { surfaceModifier -> Box(surfaceModifier.fillMaxSize()) },
        compact = compact,
      )
    }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()
  }

  private fun countText(text: String): Int =
    composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size

  private fun heightOf(node: SemanticsNodeInteraction): Float =
    with(composeRule.density) { node.fetchSemanticsNode().boundsInRoot.height.toDp().value }

  // -------------------------------------------------------------------- url ---

  /**
   * The link is the platform's own room page, one assertion per platform.
   *
   * The whole URL is compared rather than "contains the room id": the id is the easy half, and
   * the half that goes wrong is the host — a 抖音 room shared as `www.douyin.com/<id>` reads
   * like a working link and opens nothing, because that host serves the main site rather than
   * the live one.
   */
  @Test
  fun `the share url is the platform home page plus the room id`() {
    assertEquals(
      "Douyu keys its live pages by the room id",
      "https://www.douyu.com/288016",
      shareUrl(roomOn(Platform.DOUYU, "288016")),
    )
    assertEquals(
      "Huya keys its live pages by the room id",
      "https://www.huya.com/660000",
      shareUrl(roomOn(Platform.HUYA, "660000")),
    )
    assertEquals(
      "Douyin live pages are on live.douyin.com",
      "https://live.douyin.com/612345678901",
      shareUrl(roomOn(Platform.DOUYIN, "612345678901")),
    )
    assertEquals(
      "Bilibili live pages are on live.bilibili.com",
      "https://live.bilibili.com/21452505",
      shareUrl(roomOn(Platform.BILIBILI, "21452505")),
    )
  }

  // ------------------------------------------------------------------ share ---

  /**
   * Pressing 分享 leaves the player as a system chooser holding one `ACTION_SEND`.
   *
   * The assertion walks the intent the OS would receive, because the two halves can stop
   * agreeing without anything looking broken: the chooser is what the user sees, and the payload
   * inside it is what the app they pick actually gets.
   */
  @Test
  fun `the share pill opens a chooser carrying the room link`() {
    render()

    composeRule.onNodeWithText("分享").performClick()
    composeRule.waitForIdle()

    val chooser = shadowOf(application).nextStartedActivity
    assertEquals(
      "pressing 分享 must start the system chooser rather than nothing",
      Intent.ACTION_CHOOSER,
      chooser?.action,
    )

    val send = sendIntentIn(checkNotNull(chooser) { "no activity was started at all" })
    assertEquals(
      "the payload is text, which every chat, notes and mail app accepts",
      "text/plain",
      send.type,
    )
    assertEquals(
      "the body is \"who is live: what about\" plus the link",
      "一条小团团 正在直播：今天继续冲分\nhttps://www.douyu.com/1",
      send.getStringExtra(Intent.EXTRA_TEXT),
    )
    assertEquals(
      "the subject is the room title, so a mail or note arrives titled",
      "今天继续冲分",
      send.getStringExtra(Intent.EXTRA_SUBJECT),
    )
  }

  /**
   * The chooser's payload, unwrapped.
   *
   * `Intent.createChooser` wraps the sender's intent in an `ACTION_CHOOSER` one, so the action
   * and the extras are one level down from what `startActivity` was given.
   *
   * The typed getter for that extra is API 33 and this app ships to 26, so the deprecated
   * one-argument form is the only one that compiles against the module's minSdk.
   */
  @Suppress("DEPRECATION")
  private fun sendIntentIn(chooser: Intent): Intent {
    val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
    assertEquals(
      "the chooser hands the picked app an ACTION_SEND, got ${send?.action}",
      Intent.ACTION_SEND,
      send?.action,
    )
    return checkNotNull(send) { "the chooser carries no inner intent: ${chooser.extras?.keySet()}" }
  }

  /**
   * 分享 is a 48dp target.
   *
   * It is drawn next to 关注 and is the same kind of control, so it gets the same ring rather
   * than a third size: a link nobody can hit is a link nobody sends.
   */
  @Test
  fun `the share pill is a 48dp target`() {
    render()

    val height = heightOf(composeRule.onNodeWithText("分享"))
    assertTrue("the share pill is only ${height}dp tall", height >= 48f)
  }

  // ------------------------------------------------------------ not drawn ---

  /**
   * The thumbnail window draws the picture only, so it has no 分享 either.
   *
   * The mini window and picture-in-picture compose the same player at the size of a thumbnail;
   * the row this pill lives in is dropped there, and this keeps a later change from drawing the
   * control at a size no finger can aim at.
   */
  @Test
  fun `the thumbnail window has no share control`() {
    render(compact = true)

    assertEquals(
      "the action row is not drawn in a thumbnail, so sharing cannot be either",
      0,
      countText("分享"),
    )
  }

  /**
   * Full screen has no 分享: it keeps only the picture and its floating overlay.
   *
   * Not a new rule — full screen has never drawn the action row — but the share control is new
   * in that row, so the exemption has to be asserted rather than assumed.
   */
  @Test
  fun `full screen has no share control`() {
    render(fullscreen = true)

    assertEquals("full screen drops the action row, so it drops 分享 too", 0, countText("分享"))
  }
}
