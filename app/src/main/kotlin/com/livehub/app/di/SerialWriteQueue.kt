package com.livehub.app.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A one-thread write queue for a DataStore file.
 *
 * The two credential stores used `runBlocking { dataStore.edit { … } }` directly, and their callers
 * include UI callbacks: logging in or out of Bilibili/Douyin from a settings tap, and applying a
 * login that arrived from a paired device, all performed a blocking file write on the main thread.
 * This is the same treatment [UserPreferences.update] got in round 60, kept in one place because
 * two stores need it.
 *
 * **One queue per file, shared by every instance.** Two wrapper objects over the same DataStore file
 * would otherwise have independent queues, and a write issued by the previous instance could land
 * after one issued by the next — which is exactly what a test that builds a fresh container per case
 * does. Serialising per file keeps the order the calls were made in.
 */
internal class SerialWriteQueue(threadName: String) {

  private val scope = CoroutineScope(
    SupervisorJob() + Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, threadName).apply { isDaemon = true }
    }.asCoroutineDispatcher(),
  )

  /** Writes issued and not yet on disk; see [awaitIdle]. */
  private val pending: MutableSet<Job> = ConcurrentHashMap.newKeySet()

  fun submit(block: suspend () -> Unit): Job {
    val job = scope.launch { block() }
    // Registered before the caller can observe the job, so `awaitIdle()` cannot miss a write that
    // was just issued.
    pending += job
    job.invokeOnCompletion { pending -= job }
    return job
  }

  /**
   * Completes when every write issued so far has reached disk.
   *
   * Only tests need this: they build a second store over the same DataStore file, so they read from
   * disk rather than from the issuing object's cache. Production never calls it — DataStore flushes
   * on its own, and a process dying between a tap and the write is not a state this app promises to
   * recover.
   */
  fun awaitIdle() {
    // Looped rather than one pass: joining a job can let a queued one start.
    while (true) {
      val jobs = pending.toList()
      if (jobs.isEmpty()) return
      runBlocking { jobs.joinAll() }
    }
  }
}
