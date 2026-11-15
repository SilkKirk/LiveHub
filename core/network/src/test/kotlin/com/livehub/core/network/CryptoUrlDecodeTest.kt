package com.livehub.core.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `urlDecodeOnce` must not throw on input a platform actually produced.
 *
 * The method's comment has always claimed "invalid escapes are kept verbatim rather than throwing",
 * and the reference implementation it was modelled on does exactly that
 * (`HuyaStreamUrlResolverAndroid.urlDecodeOnce`). The Kotlin was a bare `URLDecoder.decode`, which
 * throws `IllegalArgumentException` — and because that is not a `PlatformException`, nothing in the
 * stack classified it: it left `HuyaSigner`, passed the retry policy untouched (an unknown exception
 * is treated as final), and reached `MainViewModel`, whose catch shows `error.message` verbatim. The
 * user saw a JDK sentence instead of a failure message.
 *
 * JUnit 5 in this module: assertion messages go **last**.
 */
class CryptoUrlDecodeTest {

  @Test
  fun `a valid escape is decoded`() {
    assertEquals("英雄联盟", Crypto.urlDecodeOnce("%E8%8B%B1%E9%9B%84%E8%81%94%E7%9B%9F"))
  }

  @Test
  fun `a plus sign keeps the form semantics URLDecoder gives it`() {
    // Not a choice this class makes; it documents that the helper is URLDecoder, not a URI decoder.
    assertEquals("a b", Crypto.urlDecodeOnce("a+b"))
  }

  /**
   * The regression: an escape that cannot be decoded comes back unchanged.
   *
   * `%zz` and a trailing `%` are both `IllegalArgumentException` from the JDK. What matters for the
   * caller is that the signature is computed over *something*, so a CDN that answers 403 is a
   * failure the user can retry, rather than a stack-trace sentence on screen.
   */
  @Test
  fun `an invalid escape is returned unchanged instead of throwing`() {
    assertEquals("%zz", Crypto.urlDecodeOnce("%zz"))
    assertEquals("100%", Crypto.urlDecodeOnce("100%"))
    assertEquals("stream%2", Crypto.urlDecodeOnce("stream%2"))
  }

  @Test
  fun `a string with no escapes passes through`() {
    assertEquals("wsSecret=abc", Crypto.urlDecodeOnce("wsSecret=abc"))
  }

  @Test
  fun `an empty string stays empty`() {
    assertEquals("", Crypto.urlDecodeOnce(""))
  }
}
