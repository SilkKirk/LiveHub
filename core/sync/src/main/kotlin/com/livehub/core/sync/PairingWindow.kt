package com.livehub.core.sync

/**
 * The six digit code another device types to pair with this one.
 *
 * This mirrors WeChat's input-method pairing: one device displays a short code, the other
 * types it, and nobody has to read an IP address off a screen. The code is the *only*
 * credential during the handshake, so it is deliberately short lived and dies on first
 * use.
 *
 * **Failures are counted per source address, not globally.** Pairing probes every address
 * on the local subnet, so any other LiveHub device on the same Wi-Fi receives the typed
 * code too and rejects it. A single global counter would therefore let two innocuous
 * mistypes kill the code on a device that was never even involved — the user would see
 * "尝试次数过多" without ever having typed that device's code.
 */
class PairingWindow(
  private val clock: () -> Long = System::currentTimeMillis,
  private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
  private val random: () -> Int = { (0..999_999).random() },
) {
  /** A code currently showing on screen. */
  data class Active(
    val code: String,
    val issuedAt: Long,
    val expiresAt: Long,
  ) {
    fun isExpired(now: Long): Boolean = now >= expiresAt

    val remainingMillis: Long get() = (expiresAt - issuedAt).coerceAtLeast(0)
  }

  @Volatile private var active: Active? = null

  /**
   * Wrong guesses per source address.
   *
   * Keyed by the caller's IP as seen by the server. A stranger gets [MAX_FAILED_ATTEMPTS]
   * guesses and no more, while a mis-swept neighbour device costs this device nothing.
   */
  private val failuresBySource = java.util.concurrent.ConcurrentHashMap<String, Int>()

  /** The code on screen right now, or null when nothing is being shown. */
  val current: Active? get() = active?.takeIf { !it.isExpired(clock()) }

  /**
   * Issues a fresh code, replacing whatever was showing.
   *
   * Zero-padded to six digits so the field on the other side does not have to accept a
   * five digit code: `12345` is a valid random draw but a confusing thing to type.
   */
  fun issue(): Active {
    val now = clock()
    val code = random().coerceIn(0, 999_999).toString().padStart(CODE_LENGTH, '0')
    val next = Active(code = code, issuedAt = now, expiresAt = now + ttlMillis)
    synchronized(this) {
      active = next
      failuresBySource.clear()
    }
    return next
  }

  /** Re-issues only when there is no open code, so the UI can call this on every render. */
  fun currentOrIssue(): Active = current ?: issue()

  fun cancel() {
    synchronized(this) {
      active = null
      failuresBySource.clear()
    }
  }

  /** Guesses [source] has already spent against the current code. */
  fun failedAttemptsBy(source: String): Int = failuresBySource[source] ?: 0

  /**
   * Checks a typed [code] from [source] and closes the window when it matched.
   *
   * A wrong guess is counted against that source; once it spends
   * [MAX_FAILED_ATTEMPTS] the code stops working *for it*. The code itself stays alive for
   * the device that is actually trying to pair, which is what makes counting per source
   * worth the extra map.
   */
  fun verify(code: String, source: String = DEFAULT_SOURCE): PairingVerdict {
    val now = clock()
    synchronized(this) {
      val shown = active ?: return PairingVerdict.NoCode
      if (shown.isExpired(now)) {
        active = null
        failuresBySource.clear()
        return PairingVerdict.Expired
      }
      if (failedAttemptsBy(source) >= MAX_FAILED_ATTEMPTS) {
        return PairingVerdict.TooManyAttempts
      }

      val normalized = code.filter(Char::isDigit)
      if (normalized.length != CODE_LENGTH || normalized != shown.code) {
        failuresBySource[source] = failedAttemptsBy(source) + 1
        return if (failedAttemptsBy(source) >= MAX_FAILED_ATTEMPTS) {
          PairingVerdict.TooManyAttempts
        } else {
          PairingVerdict.WrongCode
        }
      }

      // Paired: a used code must never work again, for anyone.
      active = null
      failuresBySource.clear()
      return PairingVerdict.Accepted
    }
  }

  companion object {
    const val CODE_LENGTH = 6

    /** Same five minute window WeChat's input method uses. */
    const val DEFAULT_TTL_MILLIS: Long = 5 * 60 * 1000

    /**
     * Guesses one address may make against a single code.
     *
     * The space is only a million codes, and a LAN attacker can send requests far faster
     * than a human can type, so an unlimited window would be brute-forceable in minutes.
     * Five guesses per address keeps a typo or two recoverable and the code out of reach.
     */
    const val MAX_FAILED_ATTEMPTS = 5

    /** Source key used when the caller does not know where the request came from. */
    const val DEFAULT_SOURCE = "local"
  }
}

/** Outcome of checking a typed pairing code. */
sealed interface PairingVerdict {
  data object Accepted : PairingVerdict

  data object WrongCode : PairingVerdict

  data object Expired : PairingVerdict

  /** No code is being shown at all, e.g. this device is the one typing. */
  data object NoCode : PairingVerdict

  data object TooManyAttempts : PairingVerdict

  /** User presentable reason, or null when the code was accepted. */
  val reason: String?
    get() = when (this) {
      Accepted -> null
      WrongCode -> "配对码不正确"
      Expired -> "配对码已过期，请在另一台设备重新查看"
      NoCode -> "本机尚未生成配对码"
      TooManyAttempts -> "尝试次数过多，请重新查看匹配码"
    }
}
