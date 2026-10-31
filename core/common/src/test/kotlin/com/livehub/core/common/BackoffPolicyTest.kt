package com.livehub.core.common

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds

class BackoffPolicyTest {

  @Test
  fun `delay doubles until the cap and then stays`() {
    val policy = BackoffPolicy(
      initialDelay = 100.milliseconds,
      maxDelay = 800.milliseconds,
      multiplier = 2.0,
    )
    assertEquals(100.milliseconds, policy.next())
    assertEquals(200.milliseconds, policy.next())
    assertEquals(400.milliseconds, policy.next())
    assertEquals(800.milliseconds, policy.next())
    assertEquals(800.milliseconds, policy.next())
  }

  @Test
  fun `reset returns to the initial delay`() {
    val policy = BackoffPolicy(initialDelay = 100.milliseconds, maxDelay = 1_000.milliseconds)
    policy.next()
    policy.next()
    policy.reset()
    assertEquals(100.milliseconds, policy.peek())
  }

  @Test
  fun `invalid configuration is rejected eagerly`() {
    assertThrows<IllegalArgumentException> {
      BackoffPolicy(initialDelay = (-1).milliseconds, maxDelay = 10.milliseconds)
    }
    assertThrows<IllegalArgumentException> {
      BackoffPolicy(initialDelay = 100.milliseconds, maxDelay = 10.milliseconds)
    }
    assertThrows<IllegalArgumentException> {
      BackoffPolicy(multiplier = 1.0)
    }
  }

  @Test
  fun `sleepNext suspends and returns the awaited delay`() = runTest {
    val policy = BackoffPolicy(initialDelay = 10.milliseconds, maxDelay = 100.milliseconds)
    val waited = policy.sleepNext()
    assertEquals(10.milliseconds, waited)
    assertTrue(policy.peek() > 10.milliseconds)
  }

  @Test
  fun `peek does not advance the sequence`() {
    val policy = BackoffPolicy(initialDelay = 50.milliseconds, maxDelay = 500.milliseconds)
    assertEquals(50.milliseconds, policy.peek())
    assertEquals(50.milliseconds, policy.peek())
    assertNull(null)
  }
}
