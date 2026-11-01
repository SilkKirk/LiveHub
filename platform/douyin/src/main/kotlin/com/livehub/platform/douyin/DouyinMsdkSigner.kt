package com.livehub.platform.douyin

/**
 * Produces the `signature` parameter Douyin's danmaku websocket requires.
 *
 * The handshake is guarded by a value computed by the site's own `webmssdk.js`: the page
 * reduces the connection parameters to an MD5 "stub" and calls `getMSSDKSignature(stub,
 * ua)`. There is no published algorithm and the emitted token changes on every call, so —
 * exactly like Douyu's `ub98484234` — the script has to be executed rather than
 * reimplemented.
 *
 * The interface lives here so [DouyinAdapter] can depend on it while the implementation (and
 * the JS engine plus the bundled bundle) stays in the app module, keeping this module free
 * of Android dependencies and its tests on the JVM.
 */
fun interface DouyinMsdkSigner {
  /**
   * Runs `getMSSDKSignature(md5Stub, userAgent)`.
   *
   * @param roomId the **numeric** room id (`room.id_str`), not the URL's `web_rid`.
   * @return a non-empty token, or null when the script could not produce one.
   */
  fun signature(roomId: String, userUniqueId: String, webcastSdkVersion: String, userAgent: String): String?

  companion object {
    /** Version echoed into the stub; the site ships 1.3.0. */
    const val WEBCAST_SDK_VERSION = "1.3.0"

    /**
     * The stub's keys, **in this exact order**.
     *
     * The stub is `md5("k1=v1,k2=v2,…")` over this list; changing the order or the set
     * produces a different digest, and the server rejects the resulting token.
     */
    val MS_STUB_KEYS = listOf(
      "live_id",
      "aid",
      "version_code",
      "webcast_sdk_version",
      "room_id",
      "sub_room_id",
      "sub_channel_id",
      "did_rule",
      "user_unique_id",
      "device_platform",
      "device_type",
      "ac",
      "identity",
    )

    /** Fixed values the web client sends for the stub. */
    private val MS_STUB_FIXED = linkedMapOf(
      "live_id" to "1",
      "aid" to "6383",
      "version_code" to "180800",
      "sub_room_id" to "",
      "sub_channel_id" to "",
      "did_rule" to "3",
      "device_platform" to "web",
      "device_type" to "",
      "ac" to "",
      "identity" to "audience",
    )

    /**
     * Builds the MD5 stub the signature is computed over.
     *
     * Exposed for tests: it is the only deterministic part of the handshake, so it is what
     * pins the parameter set in place.
     */
    fun msStub(roomId: String, userUniqueId: String, webcastSdkVersion: String): String {
      val values = MS_STUB_FIXED + mapOf(
        "webcast_sdk_version" to webcastSdkVersion,
        "room_id" to roomId,
        "user_unique_id" to userUniqueId,
      )
      val toSign = MS_STUB_KEYS.joinToString(",") { key -> "$key=${values[key].orEmpty()}" }
      return com.livehub.core.network.Crypto.md5Hex(toSign)
    }
  }
}
