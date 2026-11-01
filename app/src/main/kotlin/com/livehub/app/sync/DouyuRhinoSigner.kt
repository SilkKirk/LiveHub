package com.livehub.app.sync

import android.content.Context
import com.livehub.platform.douyu.DouyuScriptSigner
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable

/**
 * Evaluates Douyu's `ub98484234` signing function with Rhino.
 *
 * The endpoint that issues playback URLs rejects requests signed with a locally computed
 * digest (HTTP 403), so the site's own script has to run. Rhino is a full ES5 engine that
 * works on Android without a WebView, which matters because this runs inside a coroutine
 * on every stream open.
 *
 * Two details are load bearing:
 *  * optimization level must be `-1`, otherwise Rhino hits Android's bytecode generation
 *    limits on a script this size;
 *  * `cryptojs.min.js` has to be evaluated first, because the signing script calls into
 *    `CryptoJS` for its MD5.
 */
class DouyuRhinoSigner(private val assets: (String) -> String?) : DouyuScriptSigner {

  private val cryptoJs: String by lazy {
    assets(CRYPTO_JS_ASSET) ?: error("缺少 $CRYPTO_JS_ASSET，无法计算斗鱼签名")
  }

  override fun sign(script: String, roomId: String, did: String, tsSeconds: Long): String {
    val context = RhinoContext.enter()
    try {
      context.optimizationLevel = -1
      val scope: Scriptable = context.initStandardObjects()
      context.evaluateString(scope, cryptoJs, "cryptojs.min.js", 1, null)
      context.evaluateString(scope, script, "homeH5Enc.js", 1, null)

      val call = "ub98484234(${jsString(roomId)},${jsString(did)},$tsSeconds);"
      val result = context.evaluateString(scope, call, "sign-call", 1, null)
      val text = RhinoContext.toString(result)
      check(text.isNotBlank()) { "斗鱼签名脚本返回空值" }
      check(!text.startsWith("undefined")) { "斗鱼签名脚本未定义 ub98484234" }
      return text
    } finally {
      RhinoContext.exit()
    }
  }

  /** Escapes [input] into a JS string literal. */
  private fun jsString(input: String): String {
    val escaped = buildString {
      input.forEach { ch ->
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
    const val CRYPTO_JS_ASSET = "douyu/cryptojs.min.js"

    /** Builds a signer backed by the APK assets. */
    fun from(context: Context): DouyuRhinoSigner {
      val appContext = context.applicationContext
      return DouyuRhinoSigner { name ->
        runCatching {
          appContext.assets.open(name).bufferedReader().use { it.readText() }
        }.getOrNull()
      }
    }
  }
}
