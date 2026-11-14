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
   * Registers the periodic follow refresh, unless the user has switched it off.
   *
   * The check is not cosmetic. The worker itself also honours the preference, but a job that is
   * enqueued anyway wakes the process every 30 minutes just to answer "off" and return, so a user
   * who turned the feature off would keep paying for it after every restart — the switch would
   * look broken in the only way that matters (battery and wake-ups).
   *
   * The read is the same `runBlocking` settings read `MainViewModel` performs on its first frame
   * and it is cached afterwards, so this does not add a second disk hit. It is wrapped in
   * `runCatching` because a settings record that cannot be read must not stop the app from
   * launching at all; the fallback is the preference's own default (on).
   *
   * WorkManager can be unavailable — during unit tests, in restricted profiles, or when
   * its initializer has been disabled by another library. A background refresh is a
   * nice-to-have, so a failure here must never take the whole app down on launch.
   */
  private fun scheduleBackgroundWork() {
    val enabled = runCatching { container.preferences.settings.backgroundRefresh }.getOrDefault(true)
    if (!enabled) return
    runCatching { com.livehub.app.sync.FollowRefreshWorker.schedule(this) }
      .onFailure { android.util.Log.w("LiveHubApplication", "背景刷新调度失败", it) }
  }
}
