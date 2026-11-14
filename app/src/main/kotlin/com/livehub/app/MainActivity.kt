package com.livehub.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.livehub.app.di.AppContainer
import com.livehub.app.ui.LiveHubRoot
import com.livehub.app.ui.MainViewModel
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.player.PictureInPicture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {

  /**
   * Built through the activity's `ViewModelStore`, not by calling the constructor.
   *
   * `MainViewModel.onCleared()` is what stops the LAN sync server and cancels the danmaku,
   * discovery and countdown jobs. `onCleared` is only ever called by `ViewModelStore.clear()`,
   * so the `MainViewModel(container)` this activity used to build inline never had it run at
   * all: the sync server kept its port bound from the first visit to the sync screen until the
   * process died, and the six digit pairing code stayed valid long after it had scrolled off the
   * screen — `PairingWindow` is explicit that a code must not outlive the page it was shown on.
   *
   * `ViewModelProvider(this, factory)` is the same store `by viewModels()` would reach; it is
   * spelled out because it needs no delegate import and reads as one step.
   */
  private lateinit var viewModel: MainViewModel

  /**
   * `Activity.isInPictureInPictureMode`, in a form the Compose tree can observe.
   *
   * The activity gets a callback when the window shrinks or is expanded again; a composable cannot
   * poll for it, and a `Configuration` change is not how the mode is reported. The player reads this
   * to drop everything that only makes sense at full size.
   */
  private val inPictureInPicture = MutableStateFlow(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    val container = (application as LiveHubApplication).container
    viewModel = ViewModelProvider(this, ContainerViewModelFactory(container))
      .get(MainViewModel::class.java)
    setContent {
      // The `MutableStateFlow` *is* the `StateFlow` the shell reads; wrapping it in `asStateFlow()`
      // here would call a flow operator from inside composition, which lint (rightly) rejects.
      LiveHubApp(viewModel, inPictureInPicture)
    }
  }

  /**
   * Shrinks into a picture-in-picture window when the user leaves while a stream is playing.
   *
   * `onUserLeaveHint` is the platform's own "the user meant to leave" signal — Home, Recents, the
   * app switcher — and it is not called for a notification or an incoming call, which is exactly
   * the distinction wanted here. There is no button for any of this: the gesture the user already
   * makes is the trigger, and leaving a stream that is not playing simply closes the screen.
   */
  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    enterPictureInPicture()
  }

  private fun enterPictureInPicture() {
    if (isInPictureInPictureMode) return
    if (!PictureInPicture.shouldEnter(viewModel.state.value)) return
    // `enterPictureInPictureMode` throws when the activity is finishing or the window cannot
    // shrink (some OEM builds in freeform/split mode). A shrink that silently does not happen is
    // better than a crash on the way out of the app.
    runCatching { enterPictureInPictureMode(PictureInPicture.params()) }
  }

  override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    inPictureInPicture.value = isInPictureInPictureMode
  }

  /**
   * Every return to the foreground, including the first one.
   *
   * The LAN sync server now lives as long as the app is on screen (instead of only while the sync
   * page is open), and arriving in the foreground is also when one automatic exchange is worth
   * doing without being asked — both decisions belong to the ViewModel, which knows whether
   * anything is paired and whether the user left the feature on. See
   * [MainViewModel.onAppForeground].
   */
  override fun onStart() {
    super.onStart()
    viewModel.onAppForeground()
  }

  /**
   * Pauses the stream when the app is hidden without a thumbnail, and puts the LAN server away.
   *
   * Everything above is about keeping a stream *visible*; this is the other half of the same
   * decision. A stream the user can no longer see must not keep making noise with nothing on screen
   * to stop it — and there is no media notification to stop it with (see the round note in
   * `docs/ITERATIONS.md`). Leaving through Home or Recents opens the picture-in-picture window, and
   * an activity in that window is not stopped, so what reaches this method is "the thumbnail was
   * closed" or "the system refused to open one".
   *
   * A room in the mini window counts as watching: it is paused here just the same, because the mini
   * window goes away with the app.
   *
   * The sync server stop happens **before** the picture-in-picture check, and deliberately: that
   * check exists because a thumbnail is still a picture somebody is watching, while a listening
   * socket has no equivalent argument — the app is not on screen, so it must not be reachable.
   *
   * Nothing resumes automatically: coming back shows the frame the stream stopped on with the
   * 「已暂停 · 双击继续」 badge, which is also what a deliberate pause looks like.
   */
  override fun onStop() {
    super.onStop()
    viewModel.onAppBackground()
    if (isInPictureInPictureMode) return
    val state = viewModel.state.value
    if (state.room != null && !state.playerPaused) {
      viewModel.onAction(UiAction.SetPlayerPaused(true))
    }
  }
}

/** Hands [MainViewModel] its container; the ViewModel has no no-arg constructor. */
private class ContainerViewModelFactory(
  private val container: AppContainer,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
    modelClass.isAssignableFrom(MainViewModel::class.java) -> MainViewModel(container) as T
    // Loud rather than a silent ClassCastException somewhere else: a second ViewModel added to
    // this activity without a branch here would otherwise fail with an unhelpful message.
    else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}

@Composable
private fun LiveHubApp(viewModel: MainViewModel, inPictureInPicture: StateFlow<Boolean>) {
  val state by viewModel.state.collectAsState()
  val pip by inPictureInPicture.collectAsState()
  LiveHubRoot(
    state = state,
    onAction = viewModel::onAction,
    inPictureInPicture = pip,
  )
}
