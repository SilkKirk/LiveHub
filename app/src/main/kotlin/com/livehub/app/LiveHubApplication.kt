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
    scheduleBackgroundWork()
  }

  /**
   * Registers the periodic follow refresh.
   *
   * WorkManager can be unavailable — during unit tests, in restricted profiles, or when
   * its initializer has been disabled by another library. A background refresh is a
   * nice-to-have, so a failure here must never take the whole app down on launch.
   */
  private fun scheduleBackgroundWork() {
    runCatching { com.livehub.app.sync.FollowRefreshWorker.schedule(this) }
      .onFailure { android.util.Log.w("LiveHubApplication", "背景刷新调度失败", it) }
  }
}
