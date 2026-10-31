package com.livehub.platform.douyu

import com.livehub.core.model.Platform
import com.livehub.core.network.Crypto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Douyu's `getH5Play` requires a `signData` query blob that the website produces with
 * `ub98484234(rid, did, tt)` from the script served by `/swf_api/homeH5Enc`.
 *
 * The script computes, for the room id `rid`, the device id `did` and the current
 * second `tt`:
 *
 * ```
 * v      = md5(rid + did + tt)          // lowercase hex, 32 chars
 * rb     = reverse("$rid$did$v")        // per-character reversal, NOT the bytes
 * sign   = md5(rb + "$tt")              // lowercase hex, 32 chars
 * result = "v=$v&did=$did&tt=$tt&sign=$sign"
 * ```
 *
 * LiveHub implements it natively. This removes the need for a JS engine, avoids
 * shipping `cryptojs.min.js`, and makes the algorithm unit-testable.
 */
object DouyuSigner {

  /** Fixed device id used by the mobile web client. */
  const val DEFAULT_DID = "10000000000000000000000000001501"

  /** Client version string required by the play endpoint. */
  const val WEB_VER = "Douyu_223061205"

  fun sign(
    realRoomId: String,
    did: String = DEFAULT_DID,
    tt: Long = System.currentTimeMillis() / 1000,
  ): String {
    require(realRoomId.isNotBlank()) { "realRoomId must not be blank" }
    val merged = realRoomId + did + tt
    val v = Crypto.md5Hex(merged)
    val reversed = (realRoomId + did + v).reversed()
    val sign = Crypto.md5Hex(reversed + tt)
    return "v=$v&did=$did&tt=$tt&sign=$sign"
  }

  /**
   * Extracts the `signData` template from a `homeH5Enc` response.
   *
   * The endpoint returns `{"error":0,"data":{"room<rid>":"<js source>", ...}}`. The JS
   * embeds the returned value inside a call to `ub98484234`; when the server changes
   * the template, parsing it lets the client keep working without a new release.
   */
  fun parseEncSource(root: JsonObject, realRoomId: String): String? {
    val data = root["data"] as? JsonObject ?: return null
    return data["room$realRoomId"]?.jsonPrimitive?.contentOrNull
      ?: data.values.firstOrNull()?.jsonPrimitive?.contentOrNull
  }

  /** Generates the anonymous device id used by the search endpoint. */
  fun randomDid(): String {
    val seed = "${System.nanoTime()}-${(0..Int.MAX_VALUE).random()}"
    return Crypto.md5Hex(seed)
  }

  /** Platform tag used in error reporting. */
  val platform: Platform get() = Platform.DOUYU
}
