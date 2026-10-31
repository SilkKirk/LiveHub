package com.livehub.app

import android.app.Application
import com.livehub.app.di.AppContainer

/** Application entry point; owns the dependency container. */
class LiveHubApplication : Application() {

  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = AppContainer(context = this, debugLogging = BuildConfig.DEBUG)
  }
}
