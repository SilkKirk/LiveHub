package com.livehub.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.livehub.app.LiveHubApplication
import java.util.concurrent.TimeUnit

/**
 * Whether a scheduled run should actually fetch the follow list.
 *
 * Three inputs, because "the user turned it off" and "the user asked for Wi-Fi only but is on
 * mobile data" are both legitimate reasons to do nothing — and both are answered here rather than
 * inside [FollowRefreshWorker.doWork] so the truth table can be tested without a `Worker`.
 *
 * `onWifi` is nullable on purpose: `null` means the transport could not be determined (no active
 * network, a VPN tunnel hiding the transport underneath, a restricted profile, a thrown
 * platform exception). Unknown **allows** the run. The refresh ran on every network before this
 * limit existed, so reading "cannot tell" as "not Wi-Fi" would silently switch the feature off for
 * those users — the failure has to open, not close.
 */
internal fun shouldRefresh(backgroundRefresh: Boolean, wifiOnly: Boolean, onWifi: Boolean?): Boolean {
  if (!backgroundRefresh) return false
  if (!wifiOnly) return true
  return onWifi ?: true
}

/**
 * Periodically refreshes the live status of every followed streamer.
 *
 * WorkManager is used (rather than a foreground service) so the refresh survives Doze,
 * respects the user's battery settings and is automatically retried with backoff. The
 * worker never touches the network of a streamer the user removed in the meantime: it
 * re-reads the follow list from the store on every run.
 *
 * It also re-reads the two background-refresh preferences on every run, so a job that is still
 * enqueued while the user has switched the feature off costs one settings read and nothing else.
 */
class FollowRefreshWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val container = (applicationContext as? LiveHubApplication)?.container
      ?: return Result.success()
    val settings = container.preferences.settings
    val shouldRun = shouldRefresh(
      backgroundRefresh = settings.backgroundRefresh,
      wifiOnly = settings.refreshOnWifiOnly,
      onWifi = currentNetworkIsWifi(),
    )
    // `Result.success()` and not `Result.retry()` for both refusals. Retry means "this failed,
    // ask again later with backoff": neither "the switch is off" nor "the user wants Wi-Fi and
    // is on mobile data" becomes the opposite by waiting, so a retry would only re-answer the
    // same question on every backoff step while spending battery — and WorkManager counts retried
    // runs as failures, so a deliberately skipped refresh would show up as a broken job.
    if (!shouldRun) return Result.success()

    val followed = container.followStore.followed()
    if (followed.isEmpty()) return Result.success()

    var failures = 0
    val deviceId = container.preferences.deviceId
    val now = System.currentTimeMillis()
    val hlc = container.syncEngine.nextHlc()

    followed.forEach { streamer ->
      val status = runCatching {
        container.platformRepository.liveStatus(streamer.platform, streamer.roomId)
      }.getOrNull()

      if (status == null) {
        failures++
      } else {
        container.followStore.updateSnapshot(
          streamer = streamer.copy(
            isLive = status.isLive,
            title = status.title ?: streamer.title,
            viewerCount = status.viewerCount,
            coverUrl = status.coverUrl ?: streamer.coverUrl,
            updatedAt = now,
          ),
          hlc = hlc,
          deviceId = deviceId,
          now = now,
        )
      }
    }

    // A partial failure is normal (a platform may be rate limiting); only ask for a
    // retry when nothing succeeded.
    return if (failures == followed.size) Result.retry() else Result.success()
  }

  /**
   * Whether the phone is on Wi-Fi right now, or `null` when that cannot be decided.
   *
   * Every path that cannot produce an answer returns `null` rather than `false`, because
   * [shouldRefresh] treats unknown as "go ahead" and `false` would stop the refresh for anyone
   * whose network the platform does not describe well. `runCatching` covers the cases that throw
   * instead of returning nothing: a missing `CONNECTIVITY_SERVICE`, a security exception on a
   * restricted profile, and an OEM build that rejects the lookup.
   */
  private fun currentNetworkIsWifi(): Boolean? = runCatching {
    val manager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      ?: return@runCatching null
    val capabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
      ?: return@runCatching null
    when {
      capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
      // A VPN replaces the active network with its own tunnel, so the transport underneath is
      // invisible here — that is another "cannot tell", not a "not Wi-Fi".
      capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> null
      else -> false
    }
  }.getOrNull()

  companion object {
    const val UNIQUE_NAME = "livehub-follow-refresh"

    /**
     * How often the follow list is re-checked. `internal` because the settings card prints the
     * number, and a subtitle promising a different interval than the one scheduled would be a lie
     * that no test could see.
     */
    internal const val INTERVAL_MINUTES = 30L

    /** Schedules the periodic refresh; safe to call on every app start. */
    fun schedule(context: Context) {
      val request = PeriodicWorkRequestBuilder<FollowRefreshWorker>(
        INTERVAL_MINUTES,
        TimeUnit.MINUTES,
      ).build()

      WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        UNIQUE_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        request,
      )
    }

    fun cancel(context: Context) {
      WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
  }
}
