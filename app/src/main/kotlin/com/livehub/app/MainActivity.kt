package com.livehub.app

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.livehub.app.di.AppContainer
import com.livehub.app.sync.LocalNetworkAccess
import com.livehub.app.ui.LiveHubRoot
import com.livehub.app.ui.MainViewModel
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.player.PictureInPicture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

  /**
   * The video's rectangle in window coordinates, or null before the player has been laid out.
   *
   * Reported by the player composable so the shrink animates from the picture instead of from the
   * whole activity. Kept here because only an activity can put it into `PictureInPictureParams`.
   */
  private val videoBounds = MutableStateFlow<android.graphics.Rect?>(null)

  /**
   * Whether API 31+'s auto-enter is currently armed.
   *
   * Read by [onUserLeaveHint] so the two ways into the window cannot both fire: with auto-enter
   * armed the system shrinks the activity itself, and calling `enterPictureInPictureMode` as well
   * would race it.
   */
  private var autoEnterArmed = false

  /**
   * Set once the system prompt has been shown in this process.
   *
   * The user's answer is remembered by the system, so asking again on every visit to the sync page
   * would be nagging; if they denied, the sync page tells them where to change it.
   */
  private var askedLocalNetwork = false

  /**
   * The local-network permission dialog (Android 17+).
   *
   * Registered as a field, before `onCreate` returns, because `registerForActivityResult` requires
   * it. The result is handed to the ViewModel, which is what decides whether the sync surface may
   * start — see [LocalNetworkAccess].
   */
  private val localNetworkLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    viewModel.onLocalNetworkPermissionResult(granted)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    val container = (application as LiveHubApplication).container
    viewModel = ViewModelProvider(this, ContainerViewModelFactory(container) { hasLocalNetwork() })
      .get(MainViewModel::class.java)
    setContent {
      // The `MutableStateFlow` *is* the `StateFlow` the shell reads; wrapping it in `asStateFlow()`
      // here would call a flow operator from inside composition, which lint (rightly) rejects.
      LiveHubApp(
        viewModel = viewModel,
        inPictureInPicture = inPictureInPicture,
        onRequestLocalNetwork = ::requestLocalNetwork,
        onVideoBounds = ::onVideoBounds,
      )
    }
    observePictureInPicture()
  }

  /** Whether LAN traffic may proceed: always true below API 37, where the permission does not exist. */
  private fun hasLocalNetwork(): Boolean =
    !LocalNetworkAccess.enforced(Build.VERSION.SDK_INT) ||
      checkSelfPermission(LocalNetworkAccess.PERMISSION) == PackageManager.PERMISSION_GRANTED

  /**
   * Puts the system prompt in front of the user, at most once per process.
   *
   * `runCatching` because launching a permission request throws when the activity is finishing or
   * the platform has no such permission to ask for — neither is worth crashing the app over, and a
   * refusal is reported back to the ViewModel so the sync page can explain itself either way.
   */
  private fun requestLocalNetwork() {
    if (askedLocalNetwork) return
    if (!LocalNetworkAccess.enforced(Build.VERSION.SDK_INT)) return
    askedLocalNetwork = true
    runCatching { localNetworkLauncher.launch(LocalNetworkAccess.PERMISSION) }
      .onFailure { viewModel.onLocalNetworkPermissionResult(false) }
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
    // From 31 the params carry auto-enter and the system performs the shrink itself; entering
    // here as well would race it. Below 31 this hint is the only signal there is.
    if (autoEnterArmed) return
    enterPictureInPicture()
  }

  /**
   * Keeps the window's parameters in step with the state.
   *
   * Auto-enter is a *mode* the platform remembers, not a one-shot request: arming it when a room
   * opens and never disarming it means the next trip to the home screen opens a window with nothing
   * in it. So this collects the same predicate the manual path uses — [PictureInPicture.shouldEnter],
   * which includes the 画中画 switch — and re-applies the params whenever the answer flips.
   */
  private fun observePictureInPicture() {
    lifecycleScope.launch {
      viewModel.state
        .map { PictureInPicture.shouldEnter(it) }
        .distinctUntilChanged()
        .collect { armed ->
          autoEnterArmed = armed
          applyPictureInPictureParams()
        }
    }
  }

  /** The player reported where the picture is; the shrink should start from there. */
  private fun onVideoBounds(bounds: androidx.compose.ui.geometry.Rect?) {
    val rect = bounds?.let {
      android.graphics.Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
    }
    if (rect == videoBounds.value) return
    videoBounds.value = rect
    applyPictureInPictureParams()
  }

  private fun applyPictureInPictureParams() {
    val params = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      PictureInPicture.paramsWithAutoEnter(videoBounds.value, autoEnterArmed)
    } else {
      PictureInPicture.params(videoBounds.value)
    }
    // A parameter that cannot be applied is not worth crashing the screen the user is watching —
    // the same reason `enterPictureInPicture` catches.
    runCatching { setPictureInPictureParams(params) }
  }

  private fun enterPictureInPicture() {
    if (isInPictureInPictureMode) return
    if (!PictureInPicture.shouldEnter(viewModel.state.value)) return
    // `enterPictureInPictureMode` throws when the activity is finishing or the window cannot
    // shrink (some OEM builds in freeform/split mode). A shrink that silently does not happen is
    // better than a crash on the way out of the app.
    runCatching { enterPictureInPictureMode(PictureInPicture.params(videoBounds.value)) }
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
  /**
   * Whether the local-network permission is granted, read from the activity.
   *
   * A lambda rather than a stored value: the answer changes while the app runs (the user can grant
   * or revoke it in settings), and the ViewModel asks every time it is about to touch the LAN.
   */
  private val localNetworkGranted: () -> Boolean,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
    modelClass.isAssignableFrom(MainViewModel::class.java) -> MainViewModel(
      container = container,
      localNetworkGranted = localNetworkGranted,
    ) as T
    // Loud rather than a silent ClassCastException somewhere else: a second ViewModel added to
    // this activity without a branch here would otherwise fail with an unhelpful message.
    else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
  }
}

@Composable
private fun LiveHubApp(
  viewModel: MainViewModel,
  inPictureInPicture: StateFlow<Boolean>,
  onRequestLocalNetwork: () -> Unit,
  onVideoBounds: (androidx.compose.ui.geometry.Rect?) -> Unit,
) {
  val state by viewModel.state.collectAsState()
  val pip by inPictureInPicture.collectAsState()
  // The ViewModel raised the flag; only an activity can show the system dialog.
  LaunchedEffect(state.localNetworkBlocked) {
    if (state.localNetworkBlocked) onRequestLocalNetwork()
  }
  LiveHubRoot(
    state = state,
    onAction = viewModel::onAction,
    inPictureInPicture = pip,
    onVideoBounds = onVideoBounds,
  )
}
