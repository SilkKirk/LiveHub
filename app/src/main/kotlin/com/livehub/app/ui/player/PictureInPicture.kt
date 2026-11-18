package com.livehub.app.ui.player

import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import com.livehub.app.ui.UiState

/**
 * When the player may shrink into a picture-in-picture window, and the shape it takes there.
 *
 * Split out as pure functions because the window itself needs a real Activity: "is there a stream
 * worth keeping on screen" is the part that gets the rules wrong — a room that has not resolved yet
 * has no picture to show, so the thumbnail would be a postage-stamp spinner — and that part can be
 * tested without one.
 *
 * **Two ways in, and only one of them is a parameter.** Below API 31 the activity enters the window
 * from `onUserLeaveHint`, which the platform calls when the user means to leave. From 31 there is
 * `setAutoEnterEnabled`, which is a *mode* on the activity: the system performs the shrink itself.
 * A mode is remembered, so it has to be re-set on every state change — arming it once and then
 * closing the room leaves the platform opening an empty window on the next Home press.
 */
internal object PictureInPicture {

  /** 16:9: the shape of all four platforms' live streams. */
  private const val ASPECT_WIDTH = 16
  private const val ASPECT_HEIGHT = 9

  /**
   * True when there is a stream worth keeping on screen.
   *
   * All four conditions matter: the switch has to allow it, a room has to be open, a variant has to
   * be resolved, and the decoder must not have rejected that variant. The screen is deliberately
   * *not* part of the question — a room in the mini window is being watched just as much as one on
   * the player screen, and leaving the app from the list with a thumbnail in the corner should keep
   * it (not pause it and make the user come back).
   */
  fun shouldEnter(state: UiState): Boolean =
    state.pictureInPictureEnabled &&
      state.room != null &&
      state.selectedVariant != null &&
      state.playerError == null

  /**
   * The params for the window, without auto-enter — what API 26–30 can use.
   *
   * [sourceRect] is the video's rectangle in window coordinates, or null while it has not been
   * laid out yet. The system animates the shrink from there; without it the animation starts from
   * the whole activity, which looks like the app folding rather than the picture leaving.
   */
  fun params(sourceRect: android.graphics.Rect?): PictureInPictureParams =
    PictureInPictureParams.Builder()
      .setAspectRatio(Rational(ASPECT_WIDTH, ASPECT_HEIGHT))
      .apply { sourceRect?.let(::setSourceRectHint) }
      .build()

  /**
   * The same params, with auto-enter armed or disarmed — API 31 and above.
   *
   * `setAutoEnterEnabled(false)` is as load-bearing as `true`: it is how "the room closed" or "the
   * switch went off" reaches a platform that would otherwise keep entering the window on its own.
   */
  @RequiresApi(Build.VERSION_CODES.S)
  fun paramsWithAutoEnter(sourceRect: android.graphics.Rect?, autoEnter: Boolean): PictureInPictureParams =
    PictureInPictureParams.Builder()
      .setAspectRatio(Rational(ASPECT_WIDTH, ASPECT_HEIGHT))
      .setAutoEnterEnabled(autoEnter)
      .apply { sourceRect?.let(::setSourceRectHint) }
      .build()
}
