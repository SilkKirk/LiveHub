package com.livehub.core.common

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Exponential backoff used by every reconnect loop in the app (danmaku sockets,
 * stream URL refreshes, LAN peer discovery).
 *
 * The sequence starts at [initialDelay], doubles after every consecutive failure
 * and is capped at [maxDelay]. A successful attempt must call [reset] so the next
 * failure starts from the beginning again.
 */
class BackoffPolicy(
  val initialDelay: Duration = 1_000.milliseconds,
  val maxDelay: Duration = 30_000.milliseconds,
  val multiplier: Double = 2.0,
) {
  private var current: Duration = initialDelay

  init {
    require(!initialDelay.isNegative() && initialDelay > Duration.ZERO) {
      "initialDelay must be positive, was $initialDelay"
    }
    require(maxDelay >= initialDelay) { "maxDelay must be >= initialDelay" }
    require(multiplier > 1.0) { "multiplier must be > 1.0, was $multiplier" }
  }

  /** The delay that the *next* failure will wait for. */
  fun peek(): Duration = current

  /** Returns the next delay and advances the internal state. */
  fun next(): Duration {
    val result = current
    val grown = (result.inWholeMilliseconds * multiplier).toLong().milliseconds
    current = if (grown >= maxDelay) maxDelay else grown
    return result
  }

  /** Restores the initial delay after a successful attempt. */
  fun reset() {
    current = initialDelay
  }

  /** Suspends for [next] and returns the delay that was awaited. */
  suspend fun sleepNext(): Duration {
    val d = next()
    delay(d)
    return d
  }
}
