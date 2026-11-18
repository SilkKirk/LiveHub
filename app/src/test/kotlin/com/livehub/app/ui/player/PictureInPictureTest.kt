package com.livehub.app.ui.player

import android.graphics.Rect
import com.livehub.app.ui.UiState
import com.livehub.core.model.Platform
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When the app may shrink into a picture-in-picture window, and the parameters that arm it.
 *
 * The rule is a pure function of [UiState] because the mistakes here are about *state*, not about
 * windows: a room that has not resolved has no picture to show, and a forgotten reset leaves the
 * platform opening an empty thumbnail after the room is already closed. Both are cheap to pin
 * without an activity, which is the only reason this file exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PictureInPictureTest {

  private val variant = StreamVariant(
    qualityLabel = "原画",
    url = "https://example.com/a.flv",
    lineName = null,
    qualityCode = 1,
  )

  private val room = Streamer(
    platform = Platform.DOUYU,
    roomId = "1",
    streamerName = "主播",
    title = "标题",
    isLive = true,
  )

  private fun playing(
    pictureInPictureEnabled: Boolean = true,
    withRoom: Boolean = true,
    withVariant: Boolean = true,
    error: String? = null,
  ) = UiState(
    room = if (withRoom) room else null,
    selectedVariant = if (withVariant) variant else null,
    playerError = error,
    pictureInPictureEnabled = pictureInPictureEnabled,
  )

  @Test
  fun `a playing room with the switch on may shrink`() {
    assertTrue(PictureInPicture.shouldEnter(playing()))
  }

  /** The switch is not a hint: off means the stream pauses with the app like any other. */
  @Test
  fun `the switch off refuses the window even with a stream`() {
    assertFalse(PictureInPicture.shouldEnter(playing(pictureInPictureEnabled = false)))
  }

  @Test
  fun `a room without a resolved variant does not shrink`() {
    assertFalse(
      "a postage-stamp spinner is worse than staying paused",
      PictureInPicture.shouldEnter(playing(withVariant = false)),
    )
  }

  @Test
  fun `no room means no window`() {
    assertFalse(PictureInPicture.shouldEnter(playing(withRoom = false, withVariant = false)))
  }

  @Test
  fun `a variant the decoder rejected does not shrink`() {
    assertFalse(PictureInPicture.shouldEnter(playing(error = "解码失败")))
  }

  /**
   * Auto-enter is a parameter, and both of its values matter.
   *
   * Arming it is the whole point on API 31+; disarming it is what stops the platform from entering
   * a window after the room is closed — the failure recorded when this was first attempted.
   */
  @Test
  fun `auto-enter is armed and disarmed by the parameter`() {
    assertTrue(PictureInPicture.paramsWithAutoEnter(sourceRect = null, autoEnter = true).isAutoEnterEnabled)
    assertFalse(PictureInPicture.paramsWithAutoEnter(sourceRect = null, autoEnter = false).isAutoEnterEnabled)
  }

  /** The shrink animates from the picture, so the rectangle has to reach the params unchanged. */
  @Test
  fun `the source rectangle is carried into the params`() {
    val rect = Rect(0, 64, 320, 244)

    assertEquals(rect, PictureInPicture.params(rect).sourceRectHint)
    assertEquals(rect, PictureInPicture.paramsWithAutoEnter(rect, autoEnter = true).sourceRectHint)
  }

  /** Before the player has been laid out there is nothing to hint at, and nothing may be faked. */
  @Test
  fun `no rectangle means no hint`() {
    assertNull(PictureInPicture.params(sourceRect = null).sourceRectHint)
  }
}
