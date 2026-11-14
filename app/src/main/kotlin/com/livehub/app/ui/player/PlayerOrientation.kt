package com.livehub.app.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo

/**
 * Which orientation the player asks the window for, and when.
 *
 * The app derives full screen from the *window*: a landscape window is full screen
 * (`playerFullscreen`), and so is the state the 全屏 button sets. That derivation is what made the
 * button feel broken: on a phone held upright, pressing 全屏 drew the picture over the whole
 * portrait window and stopped there — the window itself never turned, so "full screen" was a
 * portrait rectangle with the chrome hidden.
 *
 * So the button now asks the activity to turn. Two rules keep it from fighting the user:
 *
 *  * **Only the button locks anything.** A phone turned sideways already plays full screen through
 *    the window, and nobody asked for that rotation; locking it would take away the rotation that
 *    got there. Physical rotation therefore never calls this.
 *  * **Anything that leaves the full player releases the lock** — minimizing to the mini window,
 *    closing the room, or the composable going away. A thumbnail in the corner must not keep the
 *    whole app sideways.
 *
 * Pure and separate from the composable for the same reason [PictureInPicture] is: the decision is
 * what gets the rules wrong, and it can be tested without a window.
 */
internal enum class PlayerOrientation {
  /** Full screen requested by the 全屏 button: the window should turn to landscape. */
  Landscape,

  /** No request of ours: the manifest's `unspecified`, i.e. follow the device. */
  Unspecified,
}

/** The orientation to request for the player's current state. */
internal fun playerOrientation(manualFullscreen: Boolean, compact: Boolean): PlayerOrientation =
  when {
    // The mini window and picture-in-picture are thumbnails inside another screen; they never
    // own the device's orientation.
    compact -> PlayerOrientation.Unspecified
    manualFullscreen -> PlayerOrientation.Landscape
    else -> PlayerOrientation.Unspecified
  }

/**
 * `SENSOR_LANDSCAPE`, not `LANDSCAPE`: both landscape directions are valid, so a phone turned the
 * other way still shows the picture upright.
 */
internal fun PlayerOrientation.toActivityInfo(): Int = when (this) {
  PlayerOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
  PlayerOrientation.Unspecified -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}

/**
 * The activity behind a composition's context.
 *
 * `LocalContext` is the activity in an app that only ever sets content from one, but it is a
 * `ContextWrapper` chain rather than an `Activity` by type, so the walk is explicit. Null when
 * there is no activity (a preview, a test harness): the caller then simply does not request
 * anything.
 */
internal fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
