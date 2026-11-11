package com.livehub.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry budget and the first-frame flag belong to **one URL**.
 *
 * Both were `remember { mutableStateOf(...) }` with no key, so they were captured on first
 * composition and never reset. The visible consequences:
 *
 *  * after one automatic retry on the stream the screen opened with, the budget was gone for the
 *    rest of the session — switching quality or line produced a URL that was never retried, so a
 *    routine CDN blip became a permanent 无法播放;
 *  * `hasFirstFrame` never resetting meant the loading scrim could not come back, so a re-resolve
 *    to a new URL showed a black rectangle instead of "正在连接直播流…".
 *
 * Neither is reachable from a screenshot (both need two URLs in sequence) and reproducing them on
 * an emulator needs a real decoder, which is why the rules live in a plain data class now.
 */
class PlaybackAttemptTest {

  private val first = "https://cdn.example.com/a.flv"
  private val second = "https://cdn.example.com/b.flv"

  @Test
  fun `a fresh attempt has its whole retry budget and shows the scrim`() {
    val attempt = PlaybackAttempt(url = first)

    assertTrue("a new stream may be retried", attempt.canRetry)
    assertTrue("a stream with no frame yet must show the scrim", attempt.showLoadingScrim)
    assertEquals(0, attempt.retriesUsed)
  }

  /** The defect: the budget was per-composition, not per-URL. */
  @Test
  fun `switching url restores the retry budget`() {
    val spent = PlaybackAttempt(url = first).afterRetry()
    assertFalse("one retry is all a single URL gets", spent.canRetry)

    val switched = spent.forUrl(second)

    assertTrue(
      "a different URL is a different stream and must be retryable again",
      switched.canRetry,
    )
    assertEquals(0, switched.retriesUsed)
    assertEquals(second, switched.url)
  }

  /** The other half of the defect: the scrim could never return. */
  @Test
  fun `switching url clears the first frame flag`() {
    val played = PlaybackAttempt(url = first).afterFirstFrame()
    assertFalse("once a frame is drawn the scrim goes away", played.showLoadingScrim)

    val switched = played.forUrl(second)

    assertTrue(
      "a new URL has not drawn anything yet, so the scrim must come back",
      switched.showLoadingScrim,
    )
  }

  /** Re-resolving the *same* URL must keep what it learned. */
  @Test
  fun `the same url keeps its flags`() {
    val played = PlaybackAttempt(url = first).afterRetry().afterFirstFrame()

    val same = played.forUrl(first)

    assertEquals(played, same)
    assertFalse("the retry was already spent on this URL", same.canRetry)
    assertFalse("the frame is still on screen", same.showLoadingScrim)
  }

  @Test
  fun `a retry is spent exactly once per url`() {
    var attempt = PlaybackAttempt(url = first)
    attempt = attempt.afterRetry()
    assertEquals(1, attempt.retriesUsed)
    assertFalse(attempt.canRetry)

    // Spending again without checking would be a bug at the call site; the model still counts it.
    assertEquals(2, attempt.afterRetry().retriesUsed)
  }

  // ------------------------------------------------------------- recovery decisions ---

  @Test
  fun `an ordinary failure retries once and then gives up`() {
    val attempt = PlaybackAttempt(url = first)

    val firstDecision = decideRecovery(
      attempt = attempt,
      error = PlaybackError(url = first, message = "连接被重置"),
      isIdnFailure = false,
      fallbackMessage = "播放器错误",
    )
    assertTrue("the first failure must be retried", firstDecision is PlaybackRecovery.Retry)
    val retried = (firstDecision as PlaybackRecovery.Retry).attempt

    val secondDecision = decideRecovery(
      attempt = retried,
      error = PlaybackError(url = first, message = "连接被重置"),
      isIdnFailure = false,
      fallbackMessage = "播放器错误",
    )
    assertEquals(
      "the second failure must be reported, not retried forever",
      PlaybackRecovery.Fatal("连接被重置"),
      secondDecision,
    )
  }

  /**
   * An IDN failure downgrades instead of retrying, and costs no budget.
   *
   * Retrying a host whose name cannot pass the SNI check is guaranteed to fail the same way; the
   * http form of the same URL works, which is why Huya's manifest permits cleartext.
   */
  @Test
  fun `an idn failure downgrades to http without spending a retry`() {
    val attempt = PlaybackAttempt(url = first)

    val decision = decideRecovery(
      attempt = attempt,
      error = PlaybackError(url = first, message = "Invalid input to toASCII"),
      isIdnFailure = true,
      fallbackMessage = "播放器错误",
    )

    assertEquals(
      PlaybackRecovery.DowngradeToHttp("http://cdn.example.com/a.flv"),
      decision,
    )
    // The attempt is untouched: the downgrade is a different URL, so it gets its own budget.
    assertTrue(attempt.canRetry)
  }

  /** A URL that is already http cannot be downgraded further. */
  @Test
  fun `an idn failure on an http url is retried normally`() {
    val plain = "http://cdn.example.com/a.flv"

    val decision = decideRecovery(
      attempt = PlaybackAttempt(url = plain),
      error = PlaybackError(url = plain, message = "toASCII"),
      isIdnFailure = true,
      fallbackMessage = "播放器错误",
    )

    assertTrue("there is nothing left to downgrade to", decision is PlaybackRecovery.Retry)
  }

  /** An error with no message of its own still produces something readable. */
  @Test
  fun `a fatal failure falls back to the caller's message`() {
    val spent = PlaybackAttempt(url = first).afterRetry()

    val decision = decideRecovery(
      attempt = spent,
      error = PlaybackError(url = first, message = null),
      isIdnFailure = false,
      fallbackMessage = "播放器错误: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
    )

    assertEquals(
      PlaybackRecovery.Fatal("播放器错误: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"),
      decision,
    )
  }

  /** A blank message is as useless as none. */
  @Test
  fun `a blank message also falls back`() {
    val spent = PlaybackAttempt(url = first).afterRetry()

    val decision = decideRecovery(
      attempt = spent,
      error = PlaybackError(url = first, message = "   "),
      isIdnFailure = false,
      fallbackMessage = "播放器错误",
    )

    assertEquals(PlaybackRecovery.Fatal("播放器错误"), decision)
  }

  /**
   * The decision must use the URL that is playing, not the one the composition started with.
   *
   * The listener is registered once (`LaunchedEffect(player)`), so it closes over whatever `url`
   * it saw. `decideRecovery` takes the URL from the *attempt* for exactly this reason, and this
   * test pins it: a downgrade has to be applied to the new URL, not the original.
   */
  @Test
  fun `the downgrade applies to the url in the attempt`() {
    val switched = PlaybackAttempt(url = first).forUrl("https://other.example.com/c.flv")

    val decision = decideRecovery(
      attempt = switched,
      error = PlaybackError(url = switched.url, message = "toASCII"),
      isIdnFailure = true,
      fallbackMessage = "播放器错误",
    )

    assertEquals(
      PlaybackRecovery.DowngradeToHttp("http://other.example.com/c.flv"),
      decision,
    )
  }
}
