package com.livehub.app

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

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    val container = (application as LiveHubApplication).container
    viewModel = ViewModelProvider(this, ContainerViewModelFactory(container))
      .get(MainViewModel::class.java)
    setContent {
      LiveHubApp(viewModel)
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
private fun LiveHubApp(viewModel: MainViewModel) {
  val state by viewModel.state.collectAsState()
  LiveHubRoot(
    state = state,
    onAction = viewModel::onAction,
  )
}
