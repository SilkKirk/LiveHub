package com.livehub.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.livehub.app.LiveHubApplication
import java.util.concurrent.TimeUnit

/**
 * Periodically refreshes the live status of every followed streamer.
 *
 * WorkManager is used (rather than a foreground service) so the refresh survives Doze,
 * respects the user's battery settings and is automatically retried with backoff. The
 * worker never touches the network of a streamer the user removed in the meantime: it
 * re-reads the follow list from the store on every run.
 */
class FollowRefreshWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val container = (applicationContext as? LiveHubApplication)?.container
      ?: return Result.success()
    val followed = container.libraryStore.followed()
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
        container.libraryStore.updateSnapshot(
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

  companion object {
    const val UNIQUE_NAME = "livehub-follow-refresh"
    private const val INTERVAL_MINUTES = 30L

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
