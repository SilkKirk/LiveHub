package com.livehub.core.sync.discovery

import com.livehub.core.sync.DiscoveredPeer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Finds which address on this network belongs to a device this installation already paired with.
 *
 * Pairing has always been able to survive a router that blocks multicast: it offers the code
 * to every address on the local /24 ([CodePairing]). Sync could not. It knew exactly one
 * address per peer — the one mDNS had advertised, or the one learned while pairing — so on a
 * network that swallows multicast the button reported "不在线" indefinitely while the other
 * device was sitting on the same Wi-Fi, reachable over plain TCP.
 *
 * The probe is `ping`, which is unauthenticated and answers with the server's device id, so a
 * sweep can identify a peer without re-pairing it. Every candidate is tried concurrently
 * (bounded by [maxConcurrent]) and the first one that answers with the wanted id wins; the
 * rest are abandoned rather than awaited, because a blocking connect cannot be interrupted
 * once it is in flight.
 */
class PeerLocator(
  /**
   * Concurrent attempts.
   *
   * A /24 is 254 addresses. Going wider risks running out of file descriptors on older
   * devices — the failure mode that would break this on exactly the cheap phones most likely
   * to need it.
   */
  private val maxConcurrent: Int = 48,
  /** Wall-clock budget, so a sweep of filtered addresses never blocks the sync button. */
  private val overallBudgetMillis: Long = 3_000,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  /**
   * Returns the address that answers with [deviceId], or null when none does.
   *
   * [probe] is blocking and is called on [dispatcher]; it returns the device id the server at
   * that address reported, or null when nothing answered.
   */
  suspend fun locate(
    deviceId: Long,
    candidates: List<DiscoveredPeer>,
    probe: (DiscoveredPeer) -> Long?,
  ): DiscoveredPeer? {
    val attempts = candidates.distinctBy { it.host to it.port }
    if (attempts.isEmpty()) return null

    val found = CompletableDeferred<DiscoveredPeer?>()
    val remaining = AtomicInteger(attempts.size)
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val gate = Semaphore(maxConcurrent)

    attempts.forEach { candidate ->
      scope.launch {
        try {
          // Checked before and after taking a permit: the winner can arrive while this
          // attempt is still queued behind others.
          if (found.isCompleted) return@launch
          gate.withPermit {
            if (found.isCompleted) return@withPermit
            val answered = try {
              probe(candidate)
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (error: Exception) {
              // A closed port, a reset, a host that is not LiveHub: all just "not this one".
              null
            }
            if (answered == deviceId) found.complete(candidate)
          }
        } finally {
          // Completes with null once every attempt finished without an answer, so a sweep of
          // closed ports reports "not found" immediately instead of sitting out the budget.
          if (remaining.decrementAndGet() == 0) found.complete(null)
        }
      }
    }

    return try {
      withTimeoutOrNull(overallBudgetMillis) { found.await() }
    } finally {
      // Not joined on purpose: a socket already connected closes when its own timeout expires.
      scope.cancel()
    }
  }
}
