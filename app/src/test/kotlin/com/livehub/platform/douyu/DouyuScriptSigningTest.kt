package com.livehub.platform.douyu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable

/**
 * Executes Douyu's real signing script with Rhino, the same engine the app uses.
 *
 * `lapi/live/getH5Play` answers HTTP 403 to the locally computed digest, so the app
 * evaluates `ub98484234` from `swf_api/homeH5Enc`. That is the one part of the playback
 * path that cannot be verified by reading code: if the script does not run, or does not
 * expose the function, no amount of correctness elsewhere gets a stream playing.
 *
 * Rhino is pure Java, so this runs on the JVM exactly as it does on device.
 */
class DouyuScriptSigningTest {

  private fun fixture(name: String): String =
    DouyuScriptSigningTest::class.java
      .getResourceAsStream("/douyu/$name")!!
      .bufferedReader()
      .use { it.readText() }

  private val cryptoJs by lazy { fixture("cryptojs.min.js") }
  private val homeH5Enc by lazy { fixture("homeH5Enc.js") }

  /** Evaluates the captured script the way DouyuRhinoSigner does. */
  private fun signWithEngine(roomId: String, did: String, ts: Long): String {
    val context = RhinoContext.enter()
    return try {
      context.optimizationLevel = -1
      val scope: Scriptable = context.initStandardObjects()
      context.evaluateString(scope, cryptoJs, "cryptojs.min.js", 1, null)
      context.evaluateString(scope, homeH5Enc, "homeH5Enc.js", 1, null)
      RhinoContext.toString(
        context.evaluateString(scope, "ub98484234(\"$roomId\",\"$did\",$ts);", "call", 1, null),
      )
    } finally {
      RhinoContext.exit()
    }
  }

  @Test
  fun `the captured script defines the signing function`() {
    assertTrue(homeH5Enc.contains("ub98484234"), "the script must define ub98484234")
    assertTrue(homeH5Enc.length > 10_000, "captured script was ${homeH5Enc.length} bytes")
  }

  @Test
  fun `the captured crypto library is present`() {
    assertTrue(cryptoJs.contains("CryptoJS"), "cryptojs should be the CryptoJS bundle")
  }

  @Test
  fun `Rhino runs the real script and yields the parameters getH5Play expects`() {
    val signed = signWithEngine(roomId = "226037", did = DID, ts = TS)

    val params = signed.split('&').associate { part ->
      val index = part.indexOf('=')
      part.substring(0, index) to part.substring(index + 1)
    }

    assertEquals(
      setOf("v", "did", "tt", "sign"),
      params.keys,
      "unexpected parameter set in: $signed",
    )
    assertEquals(DID, params["did"])
    assertEquals(TS.toString(), params["tt"])

    // The bare digits are not guaranteed to be md5: the script chooses its own encoding.
    // What the endpoint requires is a non-empty opaque value for both fields.
    assertTrue(params["v"]!!.isNotBlank(), "v must not be blank, got '$signed'")
    assertTrue(params["sign"]!!.isNotBlank(), "sign must not be blank, got '$signed'")
  }

  @Test
  fun `the signature responds to room, device and timestamp`() {
    val base = signWithEngine("226037", DID, TS)

    assertNotEquals(base, signWithEngine("226037", DID, TS + 1), "tt must matter")
    assertNotEquals(base, signWithEngine("9999", DID, TS), "room id must matter")
    assertNotEquals(base, signWithEngine("226037", OTHER_DID, TS), "did must matter")
  }

  @Test
  fun `the same inputs are stable`() {
    assertEquals(signWithEngine("226037", DID, TS), signWithEngine("226037", DID, TS))
  }

  @Test
  fun `the script output no longer matches the local formula`() {
    // The formula is the fallback and is what produced HTTP 403. Pinning the divergence
    // documents why the engine is required; if they ever match again the script has
    // rotated back and the fallback becomes sufficient on its own.
    val fromScript = signWithEngine("226037", DID, TS)
    val fromFormula = DouyuSigner.sign(realRoomId = "226037", did = DID, tt = TS)

    assertNotNull(fromScript)
    assertNotNull(fromFormula)
    assertFalse(
      fromScript == fromFormula,
      "the formula matches the live script again; the JS engine may no longer be required",
    )
  }

  @Test
  fun `signing fails loudly when the script does not define the function`() {
    val context = RhinoContext.enter()
    try {
      context.optimizationLevel = -1
      val scope: Scriptable = context.initStandardObjects()
      context.evaluateString(scope, "var unrelated = 1;", "empty.js", 1, null)
      val result = RhinoContext.toString(
        context.evaluateString(scope, "typeof ub98484234;", "probe", 1, null),
      )
      assertEquals("undefined", result)
    } finally {
      RhinoContext.exit()
    }
  }

  private companion object {
    const val DID = "10000000000000000000000000001501"
    const val OTHER_DID = "10000000000000000000000000001502"
    const val TS = 1_793_500_000L
  }
}
