package com.livehub.app.ui.player

import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.core.model.Platform
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When the player is allowed to shrink into a thumbnail.
 *
 * The window needs a device, but the decision does not — and the decision is where this goes wrong:
 * popping into a floating window over a room that has not resolved yet shows a spinner the size of a
 * postage stamp, and doing it from another screen hijacks whatever the user was looking at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PictureInPictureTest {

  private val original = StreamVariant(qualityLabel = "原画", url = "https://example.com/a.flv")

  private fun playerState(
    screen: Screen = Screen.Player,
    selected: StreamVariant? = original,
    playerError: String? = null,
  ) = UiState(
    screen = screen,
    platform = Platform.DOUYU,
    room = Streamer(
      platform = Platform.DOUYU,
      roomId = "1",
      streamerName = "主播",
      title = "标题",
      isLive = true,
    ),
    variants = listOfNotNull(selected),
    selectedVariant = selected,
    playerError = playerError,
  )

  @Test
  fun `a room with a resolved stream may shrink`() {
    assertTrue(PictureInPicture.shouldEnter(playerState()))
  }

  @Test
  fun `a room that has not resolved yet may not`() {
    assertFalse(
      "the window would be a spinner the size of a thumbnail",
      PictureInPicture.shouldEnter(playerState(selected = null)),
    )
  }

  @Test
  fun `a stream the decoder rejected may not`() {
    assertFalse(PictureInPicture.shouldEnter(playerState(playerError = "无法解码该流")))
  }

  @Test
  fun `leaving from another screen may not`() {
    assertFalse(PictureInPicture.shouldEnter(playerState(screen = Screen.Home)))
    assertFalse(PictureInPicture.shouldEnter(playerState(screen = Screen.Follow)))
  }

  @Test
  fun `the window keeps the stream's 16 by 9 shape`() {
    val params = PictureInPicture.params()

    assertNotNull(params.aspectRatio)
    assertEquals(16, params.aspectRatio!!.numerator)
    assertEquals(9, params.aspectRatio!!.denominator)
  }
}
