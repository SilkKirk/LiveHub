package com.livehub.core.network

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one behavioural difference from `runCatching`: cancellation is not a failure.
 *
 * Both halves matter and are asserted together, because a helper that rethrew *everything* would
 * pass the cancellation test and silently break every lenient fallback in the four platform
 * modules.
 *
 * JUnit 5 in this module: assertion messages go **last**.
 */
class RunCatchingCancellableTest {

  @Test
  fun `a success is returned`() = runBlocking {
    assertEquals(7, runCatchingCancellable { 7 }.getOrNull())
  }

  @Test
  fun `an ordinary failure is captured, exactly as runCatching would`() = runBlocking {
    val result = runCatchingCancellable<Int> { throw IllegalStateException("boom") }

    assertTrue(result.isFailure, "a thrown exception has to be captured, not propagated")
    assertEquals("boom", result.exceptionOrNull()?.message)
  }

  /** The regression: the fallbacks downstream must not run for a screen that has gone away. */
  @Test
  fun `cancellation is rethrown rather than captured`() {
    val thrown = assertThrows(CancellationException::class.java) {
      runBlocking {
        runCatchingCancellable<Int> { throw CancellationException("superseded") }
      }
    }

    assertEquals("superseded", thrown.message)
  }

  /** An `Error` is still captured, which is what `runCatching` does and what callers rely on. */
  @Test
  fun `an error is captured too`() = runBlocking {
    val result = runCatchingCancellable<Int> { throw AssertionError("not a cancellation") }

    assertTrue(result.isFailure)
    assertFalse(result.isSuccess)
  }
}
