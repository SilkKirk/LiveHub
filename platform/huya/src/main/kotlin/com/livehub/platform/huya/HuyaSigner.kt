package com.livehub.platform.huya

import com.livehub.core.network.Crypto
import kotlin.random.Random

/**
 * Huya's `wsSecret` anti-leech signature.
 *
 * Given the `sFlvAntiCode` query string from the room page, plus a random uid and the
 * current time, the algorithm is:
 *
 * ```
 * fmPlain  = base64(urlDecode(fm))
 * wsPrefix = fmPlain.substringBefore('_')
 * wsSecretHash = md5("${uid + t13}|$ctype|100")
 * wsSecret     = md5("$wsPrefix_${uid}_${streamName}_${wsSecretHash}_$wsTime")
 * ```
 *
 * where `wsTime` is the lowercase hex of `(t13 + 110624) / 1000`. `wsSecretHash` itself
 * is produced as `md5(seqid|ctype|t)` with the parameters kept literally as `100`;
 * that value is embedded in the plain string that is hashed a second time.
 */
object HuyaSigner {
  const val PARAMS_T = 100
  const val SDK_VERSION = 2403051612L
  const val UID_BASE = 1_400_000_000_000L
  const val UID_RANGE = 10_000_000_000L
  const val WS_TIME_OFFSET = 110_624L
  const val UINT32_MAX = 4_294_967_295L

  data class AntiCode(
    val fm: String,
    val ctype: String,
    val fs: String,
  )

  /** Parses an `sFlvAntiCode` value (`fm=...&ctype=...&fs=...`). */
  fun parseAntiCode(raw: String): AntiCode? {
    val decoded = raw.replace("&amp;", "&")
    if (decoded.isBlank()) return null
    val params = decoded.split('&')
      .mapNotNull { pair ->
        val index = pair.indexOf('=')
        if (index <= 0) null else pair.substring(0, index) to pair.substring(index + 1)
      }
      .toMap()
    val fm = params["fm"] ?: return null
    val ctype = params["ctype"] ?: return null
    val fs = params["fs"] ?: return null
    return AntiCode(fm = fm, ctype = ctype, fs = fs)
  }

  /**
   * Computes the signed query string, in the order the Huya CDN expects.
   *
   * @param t13 epoch milliseconds; injectable so tests are deterministic.
   * @param uid random client id; injectable for tests.
   */
  fun signQuery(
    antiCode: AntiCode,
    streamName: String,
    t13: Long,
    uid: Long,
    randomTail: Long = 0,
  ): String {
    val fmPlain = String(Crypto.base64Decode(Crypto.urlDecodeOnce(antiCode.fm)), Charsets.UTF_8)
    val wsPrefix = fmPlain.substringBefore('_')
    val sdkSid = t13
    val seqId = uid + sdkSid
    val wsTime = java.lang.Long.toHexString((t13 + WS_TIME_OFFSET) / 1000)
    val uuid = ((t13 % 10_000_000_000L) * 1000 + (randomTail and 0x7FFFFFFF) % 1000) % UINT32_MAX
    val wsSecretHash = Crypto.md5Hex("$seqId|${antiCode.ctype}|$PARAMS_T")
    val wsSecretPlain = "${wsPrefix}_${uid}_${streamName}_${wsSecretHash}_$wsTime"
    val wsSecret = Crypto.md5Hex(wsSecretPlain)

    return buildString {
      append("wsSecret=").append(wsSecret)
      append("&wsTime=").append(wsTime)
      append("&seqid=").append(seqId)
      append("&ctype=").append(antiCode.ctype)
      append("&ver=1")
      append("&fs=").append(antiCode.fs)
      append("&uuid=").append(uuid)
      append("&u=").append(uid)
      append("&t=").append(PARAMS_T)
      append("&sv=").append(SDK_VERSION)
      append("&sdk_sid=").append(sdkSid)
      append("&codec=264")
    }
  }

  /** Builds the full playable URL for one stream entry. */
  fun buildStreamUrl(
    flvUrl: String,
    streamName: String,
    suffix: String,
    antiCode: AntiCode,
    nowMillis: Long = System.currentTimeMillis(),
    random: Random = Random.Default,
  ): String {
    val uid = UID_BASE + (random.nextLong().let { if (it == Long.MIN_VALUE) 0L else kotlin.math.abs(it) } % UID_RANGE)
    val query = signQuery(
      antiCode = antiCode,
      streamName = streamName,
      t13 = nowMillis,
      uid = uid,
      randomTail = random.nextLong(),
    )
    return "${flvUrl.trimEnd('/')}/$streamName.$suffix?$query"
  }

  /** CDN preference; `tx` is avoided because its redirect hosts break Android TLS. */
  fun cdnRank(cdnType: String): Int = when (cdnType.lowercase()) {
    "al" -> 0
    "hs" -> 1
    "tx" -> 2
    else -> 3
  }

  /**
   * Rewrites the `tx` CDN parameters that are incompatible with the H5 player, then
   * downgrades the scheme.
   *
   * The patterns are deliberately **not** anchored on a leading `&`. `signQuery` emits
   * `wsSecret`, `wsTime`, `seqid`, `ctype`, … so `ctype` is the fourth parameter and an
   * `&ctype=` pattern would match today; the unanchored form is used so the rewrite cannot
   * silently stop working if that order ever changes. Nothing else in the query string ends
   * in `ctype`, so the shorter pattern has no false positives to collide with.
   */
  fun postProcess(url: String, cdnType: String): String {
    val rewritten = if (cdnType.equals("tx", ignoreCase = true)) {
      url.replace("ctype=tars_mp", "ctype=huya_webh5")
        .replace("fs=bhct", "fs=bgct")
    } else {
      url
    }
    // Huya's CDN hosts use underscores that break SNI validation on Android; http works.
    return rewritten.replaceFirst("https://", "http://")
  }
}
