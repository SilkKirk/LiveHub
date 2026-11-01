package com.livehub.platform.douyu

/**
 * Evaluates Douyu's playback signing script.
 *
 * `lapi/live/getH5Play` is guarded by a signature produced by `ub98484234`, a function in
 * the JS served from `swf_api/homeH5Enc`. Douyu rotates that script, and the fixed digest
 * this project used to compute locally is now rejected with HTTP 403 — so the script has
 * to be executed rather than reimplemented.
 *
 * The interface lives here so [DouyuApi] can depend on it while the implementation (and
 * the JS engine plus bundled crypto library) stays in the app module, keeping this module
 * free of Android dependencies and its tests on the JVM.
 */
fun interface DouyuScriptSigner {
  /**
   * Runs `ub98484234(roomId, did, tsSeconds)` against [script].
   *
   * @return the `key=value&...` blob the playback endpoint expects. Implementations
   *   should throw on failure; [DouyuApi] falls back to the pure-Kotlin formula.
   */
  fun sign(script: String, roomId: String, did: String, tsSeconds: Long): String
}
