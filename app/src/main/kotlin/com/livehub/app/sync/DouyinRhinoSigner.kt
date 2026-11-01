package com.livehub.app.sync

import android.content.Context
import com.livehub.platform.douyin.DouyinMsdkSigner
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable

/**
 * Evaluates Douyin's `webmssdk.js` with Rhino.
 *
 * The bundle is ~636 KB of mostly-obfuscated JavaScript and the token it returns changes on
 * every call, so it is executed rather than reimplemented (the same reasoning as
 * [DouyuRhinoSigner]). Two details matter:
 *
 *  * optimisation level must be `-1`, otherwise Rhino tries to emit JVM bytecode for a
 *    script of this size and hits Android's method limits;
 *  * the bundle expects a browser-ish global scope, so `window`, `document`, `navigator`
 *    and `location` are seeded before it runs. Without them it throws while installing its
 *    fingerprint probes.
 *
 * The signature is retried a few times because the script occasionally emits an empty or
 * punctuated value; the server only accepts a bare token.
 */
class DouyinRhinoSigner(private val assets: () -> String?) : DouyinMsdkSigner {

  private val script: String? by lazy { assets() }

  override fun signature(
    roomId: String,
    userUniqueId: String,
    webcastSdkVersion: String,
    userAgent: String,
  ): String? {
    val source = script ?: return null
    val stub = DouyinMsdkSigner.msStub(
      roomId = roomId,
      userUniqueId = userUniqueId,
      webcastSdkVersion = webcastSdkVersion,
    )
    val context = RhinoContext.enter()
    try {
      context.optimizationLevel = -1
      val scope: Scriptable = context.initStandardObjects()
      seedBrowserGlobals(context, scope, userAgent)
      context.evaluateString(scope, source, "webmssdk.js", 1, null)

      val call = "getMSSDKSignature(${stub.jsLiteral()}, ${userAgent.jsLiteral()});"
      repeat(MAX_ATTEMPTS) {
        val value = context.evaluateString(scope, call, "getMSSDKSignature", 1, null)
        val token = RhinoContext.toString(value).trim()
        if (token.isNotEmpty() && token.none { it == '-' || it == '=' }) {
          return token
        }
      }
      return null
    } catch (error: Exception) {
      return null
    } finally {
      RhinoContext.exit()
    }
  }

  /** Installs the handful of browser globals the bundle probes during start-up. */
  private fun seedBrowserGlobals(context: RhinoContext, scope: Scriptable, userAgent: String) {
    context.evaluateString(
      scope,
      """
      var window = this;
      var navigator = { userAgent: ${userAgent.jsLiteral()}, platform: 'Win32', language: 'zh-CN' };
      window.navigator = navigator;
      window.location = { href: 'https://live.douyin.com/', protocol: 'https:', host: 'live.douyin.com' };
      window.document = { cookie: '', referrer: 'https://live.douyin.com/', createElement: function () { return {}; }, documentElement: {} };
      window.screen = { width: 1920, height: 1080, availWidth: 1920, availHeight: 1040 };
      window.setTimeout = function (fn) { return 0; };
      window.clearTimeout = function () {};
      window.btoa = function (s) { return s; };
      """.trimIndent(),
      "browser-globals",
      1,
      null,
    )
  }

  /** Escapes [this] into a JavaScript string literal. */
  private fun String.jsLiteral(): String {
    val escaped = buildString {
      this@jsLiteral.forEach { ch ->
        when (ch) {
          '\\' -> append("\\\\")
          '"' -> append("\\\"")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          else -> append(ch)
        }
      }
    }
    return "\"$escaped\""
  }

  companion object {
    const val WEBSDK_ASSET = "douyin/webmssdk.js"

    /** The token changes per call; a few attempts is enough to get a clean one. */
    private const val MAX_ATTEMPTS = 12

    /** Builds a signer backed by the APK assets. */
    fun from(context: Context): DouyinRhinoSigner {
      val appContext = context.applicationContext
      return DouyinRhinoSigner {
        runCatching {
          appContext.assets.open(WEBSDK_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()
      }
    }
  }
}
