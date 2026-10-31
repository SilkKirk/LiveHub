package com.livehub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.livehub.app.ui.LiveHubRoot
import com.livehub.app.ui.MainViewModel

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    val container = (application as LiveHubApplication).container
    val viewModel = MainViewModel(container)
    setContent {
      LiveHubApp(viewModel)
    }
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
