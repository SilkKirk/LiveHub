package com.livehub.core.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The six digit matching code: what makes it live, and what kills it.
 *
 * These matter because the code is the *only* credential during pairing — there is no
 * address check and no account. A code that outlives its window, survives its own use, or
 * shrugs off unlimited guesses is a hole rather than a feature.
 */
class PairingWindowTest {

  private var now = 1_000_000L

  private fun window(ttl: Long = PairingWindow.DEFAULT_TTL_MILLIS) =
    PairingWindow(clock = { now }, ttlMillis = ttl)

  private fun wrongFor(code: String): String =
    if (code == "000000") "000001" else "000000"

  @Test
  fun `code is six digits zero padded`() {
    val window = PairingWindow(clock = { now }, random = { 42 })
    val code = window.issue().code

    assertEquals("000042", code)
    assertEquals(PairingWindow.CODE_LENGTH, code.length)
    assertTrue(code.all { it.isDigit() })
  }

  @Test
  fun `no code is shown until one is issued`() {
    val window = window()

    assertNull(window.current)
    assertEquals(PairingVerdict.NoCode, window.verify("123456"))
  }

  @Test
  fun `currentOrIssue issues once and then stays put`() {
    val window = window()

    val first = window.currentOrIssue()
    val second = window.currentOrIssue()

    assertEquals(first.code, second.code)
    assertEquals(first.issuedAt, second.issuedAt)
  }

  @Test
  fun `the right code is accepted exactly once`() {
    val window = window()
    val code = window.issue().code

    assertEquals(PairingVerdict.Accepted, window.verify(code))
    // A used code must never work again, not even for the device that just used it.
    assertEquals(PairingVerdict.NoCode, window.verify(code))
    assertNull(window.current)
  }

  @Test
  fun `a wrong code is rejected and the window survives`() {
    val window = window()
    val code = window.issue().code

    assertEquals(PairingVerdict.WrongCode, window.verify(wrongFor(code)))
    // Still open: one mistyped digit should not force the user back to the other device.
    assertEquals(code, window.current?.code)
    assertEquals(PairingVerdict.Accepted, window.verify(code))
  }

  @Test
  fun `code expires after its window`() {
    val window = window(ttl = 60_000)
    val code = window.issue().code

    now += 59_999
    assertNotNull(window.current)

    now += 1
    assertNull(window.current)
    assertEquals(PairingVerdict.Expired, window.verify(code))
  }

  @Test
  fun `expiry is fatal so an expired code cannot be revived`() {
    val window = window(ttl = 1_000)
    val code = window.issue().code

    now += 1_001
    assertEquals(PairingVerdict.Expired, window.verify(code))
    // The window was cleared, not merely refused: verifying again is "no code", not a
    // second chance at the same digits.
    assertEquals(PairingVerdict.NoCode, window.verify(code))
  }

  @Test
  fun `guessing is capped so six digits cannot be brute forced`() {
    val window = window()
    val code = window.issue().code
    val wrong = wrongFor(code)

    repeat(PairingWindow.MAX_FAILED_ATTEMPTS - 1) {
      assertEquals(PairingVerdict.WrongCode, window.verify(wrong), "guess ${it + 1}")
    }
    assertEquals(PairingVerdict.TooManyAttempts, window.verify(wrong))
    // That source is now locked out even with the right digits.
    assertEquals(PairingVerdict.TooManyAttempts, window.verify(code))
  }

  /**
   * The cap is per source, not global.
   *
   * Pairing probes every address on the subnet, so every other LiveHub device on the Wi-Fi
   * sees the typed code and rejects it. A global counter would let two innocent mistypes
   * kill the code on a device the user never touched.
   */
  @Test
  fun `a sweeps worth of rejections from other addresses does not burn the budget`() {
    val window = window()
    val code = window.issue().code
    val wrong = wrongFor(code)

    // Ten neighbours, each rejecting once, is what a real sweep looks like.
    repeat(10) { index -> window.verify(wrong, source = "192.168.1.${index + 2}") }

    // The device that is actually pairing is unaffected and its correct code still works.
    assertEquals(PairingVerdict.Accepted, window.verify(code, source = "192.168.1.99"))
  }

  @Test
  fun `the budget is tracked per source`() {
    val window = window()
    val code = window.issue().code
    val wrong = wrongFor(code)

    window.verify(wrong, source = "10.0.0.2")
    window.verify(wrong, source = "10.0.0.2")
    window.verify(wrong, source = "10.0.0.3")

    assertEquals(2, window.failedAttemptsBy("10.0.0.2"))
    assertEquals(1, window.failedAttemptsBy("10.0.0.3"))
    assertEquals(0, window.failedAttemptsBy("10.0.0.4"))
  }

  @Test
  fun `issuing a code clears earlier attempts`() {
    val window = PairingWindow(clock = { now }, random = { 7 })
    val first = window.issue().code
    repeat(PairingWindow.MAX_FAILED_ATTEMPTS) { window.verify(wrongFor(first), source = "10.0.0.2") }
    assertEquals(PairingVerdict.TooManyAttempts, window.verify(first, source = "10.0.0.2"))

    val second = window.issue()

    assertEquals(0, window.failedAttemptsBy("10.0.0.2"))
    assertEquals(PairingVerdict.Accepted, window.verify(second.code, source = "10.0.0.2"))
  }

  @Test
  fun `a replacement code invalidates the one it replaced`() {
    val window = window()
    val old = window.issue().code
    val replacement = window.issue().code

    if (old != replacement) {
      assertEquals(PairingVerdict.WrongCode, window.verify(old))
    }
    assertEquals(PairingVerdict.Accepted, window.verify(replacement))
  }

  @Test
  fun `cancelling drops the code`() {
    val window = window()
    val code = window.issue().code

    window.cancel()

    assertNull(window.current)
    assertEquals(PairingVerdict.NoCode, window.verify(code))
  }

  @Test
  fun `verification ignores separators the user might type`() {
    val window = PairingWindow(clock = { now }, random = { 123456 })
    window.issue()

    // A code shown as "123 456" invites a space or a dash; both have to work.
    assertEquals(PairingVerdict.Accepted, window.verify("123 456"))
  }

  @Test
  fun `a code of the wrong length is refused but still costs an attempt`() {
    val window = PairingWindow(clock = { now }, random = { 40 })
    window.issue()

    assertEquals(PairingVerdict.WrongCode, window.verify("123"))
    assertEquals(1, window.failedAttemptsBy(PairingWindow.DEFAULT_SOURCE))

    window.verify("1234")
    assertEquals(2, window.failedAttemptsBy(PairingWindow.DEFAULT_SOURCE))
    assertFalse(window.failedAttemptsBy(PairingWindow.DEFAULT_SOURCE) >= PairingWindow.MAX_FAILED_ATTEMPTS)
  }

  @Test
  fun `remaining time is reported from issue, not from now`() {
    val window = window(ttl = 300_000)
    val active = window.issue()

    now += 120_000

    assertEquals(300_000L, active.remainingMillis)
    assertFalse(active.isExpired(now))
  }
}
