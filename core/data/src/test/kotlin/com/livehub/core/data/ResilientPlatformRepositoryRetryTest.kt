package com.livehub.core.data

import com.livehub.core.common.BackoffPolicy
import com.livehub.core.network.NetworkException
import com.livehub.platform.api.PlatformException
import com.livehub.platform.api.PlatformNetworkException
import com.livehub.platform.api.PlatformOfflineException
import com.livehub.platform.api.PlatformProtocolException
import kotlinx.coroutines.CancellationException
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

    val thrown = assertThrows(PlatformNetworkException::class.java) {
      runBlocking {
        repository().withRetry<Unit> {
          calls++
          throw IOException("always fails")
        }
      }
    }

    assertEquals(ResilientPlatformRepository.MAX_ATTEMPTS, calls)
    // The transport's own text is not reused: it names the library, not the user's situation.
    assertEquals("网络连接失败，请检查网络后重试", thrown.message)
    assertEquals("always fails", thrown.cause?.message)
  }

  /**
   * A transport failure becomes a typed, Chinese failure — and keeps its cause.
   *
   * OkHttp's `IOException` message is `Failed to connect to /151.101.1.6:443`, and it used to be
   * rendered verbatim: every catch site in `MainViewModel` ends with `error.message ?: "…"`, so the
   * fallback never applied. The same was true of a stray `IllegalArgumentException` from a parser.
   */
  @Test
  fun `an IOException surfaces as a network failure, not as raw library text`() {
    val thrown = assertThrows(PlatformNetworkException::class.java) {
      runBlocking {
        repository().withRetry<Unit> { throw IOException("Failed to connect to /151.101.1.6:443") }
      }
    }

    assertEquals(PlatformException.Kind.NETWORK, thrown.kind)
    assertEquals("网络连接失败，请检查网络后重试", thrown.message)
    assertEquals(
      "Failed to connect to /151.101.1.6:443",
      thrown.cause?.message,
      "the original failure has to stay reachable for debugging",
    )
  }

  /** Anything the adapters did not type becomes a protocol failure, with our own words. */
  @Test
  fun `an untyped exception surfaces as a protocol failure`() {
    val thrown = assertThrows(PlatformProtocolException::class.java) {
      runBlocking {
        repository().withRetry<Unit> { throw IllegalArgumentException("not a JSON object") }
      }
    }

    assertEquals(PlatformException.Kind.PROTOCOL, thrown.kind)
    assertEquals("平台返回了无法处理的数据，请稍后重试", thrown.message)
    assertEquals("not a JSON object", thrown.cause?.message)
  }

  /**
   * A 4xx is **not** reported as a network failure, even though it arrives as an `IOException`.
   *
   * `NetworkException` extends `IOException`, so a "wrap every IOException as 网络连接失败" rule would
   * tell the user to check their Wi-Fi over an expired signature. The status is what matters, so it
   * is named in the message and classified as a protocol failure.
   */
  @Test
  fun `an HTTP status is reported as a status, not as a connection problem`() {
    val thrown = assertThrows(PlatformProtocolException::class.java) {
      runBlocking {
        repository().withRetry<Unit> { throw NetworkException(403, "HTTP 403 for https://x") }
      }
    }

    assertEquals(PlatformException.Kind.PROTOCOL, thrown.kind)
    assertEquals("平台返回了 HTTP 403，请稍后重试", thrown.message)
  }

  /** An untyped exception is not retried either: retrying cannot make a parser understand it. */
  @Test
  fun `an untyped exception is not retried`() {
    assertEquals(1, attemptsBeforeFailure(IllegalStateException("boom")))
  }

  /** A `PlatformException` raised on purpose keeps its identity, message and kind. */
  @Test
  fun `a platform failure passes through untouched`() {
    val original = PlatformOfflineException("主播未开播")

    val thrown = assertThrows(PlatformOfflineException::class.java) {
      runBlocking { repository().withRetry<Unit> { throw original } }
    }

    assertEquals("主播未开播", thrown.message)
    assertEquals(PlatformException.Kind.OFFLINE, thrown.kind)
  }

  /** A typed network failure is retryable, which is the whole reason `Kind.NETWORK` exists. */
  @Test
  fun `a network failure is retried`() {
    assertEquals(
      ResilientPlatformRepository.MAX_ATTEMPTS,
      attemptsBeforeFailure(PlatformNetworkException("网络连接失败")),
    )
  }

  /**
   * Cancellation is rethrown as itself, not classified.
   *
   * This is the trap in the classification step: `CancellationException` is neither a
   * `PlatformException` nor an `IOException`, so a "wrap everything else" rule would have turned
   * every superseded request into a reportable protocol error — exactly the defect round 108 fixed
   * in `MainViewModel`, reintroduced one layer down.
   */
  @Test
  fun `cancellation is not classified into an error`() {
    var calls = 0

    val thrown = assertThrows(CancellationException::class.java) {
      runBlocking {
        repository().withRetry<Unit> {
          calls++
          throw CancellationException("superseded")
        }
      }
    }

    assertEquals("superseded", thrown.message)
    assertEquals(1, calls, "a cancelled request is not retried either")
  }
}
