package com.livehub.app.ui.player

import android.app.PictureInPictureParams
import android.util.Rational
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState

/**
 * When the player may shrink into a picture-in-picture window, and the shape it takes there.
 *
 * Split out as a pure function because the window itself needs a real Activity: "is there a stream
 * worth keeping on screen" is the part that gets the rules wrong — a room that has not resolved yet
 * has no picture to show, so the thumbnail would be a postage-stamp spinner — and that part can be
 * tested without one.
 *
 * There is no button for this. The trigger is the gesture the user already makes (Home, Recents, the
 * app switcher), which the platform reports as [android.app.Activity.onUserLeaveHint].
 *
 * No version guard: `minSdk` is 26 and picture-in-picture arrived in 26, so every device that can
 * install this app can shrink. A `Build.VERSION.SDK_INT` check here would be dead code that only
 * looks careful.
 */
internal object PictureInPicture {

  /** 16:9: the shape of all four platforms' live streams. */
  private const val ASPECT_WIDTH = 16
  private const val ASPECT_HEIGHT = 9

  /**
   * True when the player holds a playable stream.
   *
   * All three conditions matter: the player has to be the screen that is on, a variant has to be
   * resolved, and the decoder must not have rejected that variant.
   */
  fun shouldEnter(state: UiState): Boolean =
    state.screen == Screen.Player &&
      state.selectedVariant != null &&
      state.playerError == null

  /** The params for that window. */
  fun params(): PictureInPictureParams = PictureInPictureParams.Builder()
    .setAspectRatio(Rational(ASPECT_WIDTH, ASPECT_HEIGHT))
    .build()
}
