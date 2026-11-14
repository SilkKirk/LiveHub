package com.livehub.core.data

import com.livehub.core.common.BackoffPolicy
import com.livehub.core.network.NetworkException
import com.livehub.platform.api.PlatformOfflineException
import com.livehub.platform.api.PlatformProtocolException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Which failures are worth retrying, and how many times.
 *
 * The policy is the one place every platform call goes through, and its rule was "any
 * `IOException` is transient". `NetworkException` extends `IOException`, so every **4xx** — an
 * expired signature's 403, a 404, a rejected parameter — was retried three times with backoff
 * before being reported. That is extra latency on a failure whose answer cannot change, on the one
 * path (opening a room) where the user is watching a spinner.
 *
 * The assertions are on **how many times the block ran**, because every failing case ends with the
 * same exception being rethrown — the count is the only observable that separates "answered" from
 * "retried". The delays are 1 ms rather than the production 300 ms/2 s, so the test costs nothing
 * and still exercises the real loop.
 */
class ResilientPlatformRepositoryRetryTest {

  private fun repository(): ResilientPlatformRepository = ResilientPlatformRepository(
    gateway = PlatformGateway(MapPlatformRegistry(emptyList())),
    retryPolicy = BackoffPolicy(1.milliseconds, 2.milliseconds),
  )

  /** Raises [error] on every attempt and returns how many attempts the loop made. */
  private fun attemptsBeforeFailure(error: Throwable): Int {
    var calls = 0
    assertThrows(Throwable::class.java) {
      runBlocking {
        repository().withRetry<Unit> {
          calls++
          throw error
        }
      }
    }
    return calls
  }

  @Test
  fun `a 5xx is retried up to the attempt cap`() {
    assertEquals(
      ResilientPlatformRepository.MAX_ATTEMPTS,
      attemptsBeforeFailure(NetworkException(503, "boom")),
    )
  }

  @Test
  fun `a 429 is retried because the server asked for it`() {
    assertEquals(
      ResilientPlatformRepository.MAX_ATTEMPTS,
      attemptsBeforeFailure(NetworkException(429, "slow down")),
    )
  }

  /**
   * The regression: a 4xx is final.
   *
   * Signed room URLs expire and rooms disappear; re-fetching the whole page with backoff can fix
   * neither. This count is what fails on the old `is IOException -> true` rule.
   */
  @Test
  fun `a 403 is not retried`() {
    assertEquals(
      1,
      attemptsBeforeFailure(NetworkException(403, "forbidden")),
      "a forbidden answer is final",
    )
  }

  @Test
  fun `a 404 is not retried`() {
    assertEquals(1, attemptsBeforeFailure(NetworkException(404, "not found")))
  }

  @Test
  fun `a plain IO failure is still retried`() {
    assertEquals(
      ResilientPlatformRepository.MAX_ATTEMPTS,
      attemptsBeforeFailure(IOException("connection reset")),
    )
  }

  @Test
  fun `a domain error is answered immediately`() {
    assertEquals(1, attemptsBeforeFailure(PlatformOfflineException("主播未开播")))
  }

  @Test
  fun `a protocol failure is answered immediately`() {
    assertEquals(1, attemptsBeforeFailure(PlatformProtocolException("抖音分区列表获取失败: status=-1")))
  }

  @Test
  fun `a successful first attempt calls the block once`() {
    var calls = 0

    runBlocking { repository().withRetry { calls++ } }

    assertEquals(1, calls)
  }

  @Test
  fun `the last allowed attempt rethrows instead of looping`() {
    var calls = 0

    val thrown = assertThrows(IOException::class.java) {
      runBlocking {
        repository().withRetry<Unit> {
          calls++
          throw IOException("always fails")
        }
      }
    }

    assertEquals(ResilientPlatformRepository.MAX_ATTEMPTS, calls)
    assertEquals("always fails", thrown.message)
  }
}
