package com.livehub.platform.huya

/**
 * Decodes Huya's Tars push frames into chat messages.
 *
 * Outer frame: `{ 0: int32 pushType, 1: bytes payload }` where `pushType == 7` means a
 * user message. Inside: `{ 0: int32 0, 1: int32 messageType, 2: bytes body }` where
 * `messageType == 1400` is a chat message. The body is a struct whose tag 2 holds the
 * nickname and tag 3 the text.
 */
object HuyaDanmakuProtocol {
  const val PUSH_TYPE_USER_MESSAGE = 7
  const val MESSAGE_TYPE_CHAT = 1400
  const val HEARTBEAT_INTERVAL_MILLIS = 20_000L
  const val IDLE_RESUBSCRIBE_MILLIS = 12_000L
  const val IDLE_RECONNECT_MILLIS = 18_000L

  const val WS_URL = "wss://cdnws.api.huya.com"
  const val WS_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

  /** Decodes a chat frame, or returns null for heartbeats and other push types. */
  /**
   * Decodes a chat push into `(nickname, text)`.
   *
   * The body has two shapes in the wild and both have to be read, which a captured frame from
   * room 660000 made obvious:
   *
   * ```
   * BODY: t0:STRUCT_BEGIN | t0:LONG | t1:ZERO | t2:S1(9)="威仔zzz" | t3:ZERO | ... END
   *       t1:ZERO | t2:ZERO | t3:S1(21)="只有凌晨是直播" | ...
   * ```
   *
   * The nickname lives at tag 2 *inside* the struct at tag 0, while the visible text is at
   * tag 3 of the **body itself** — tag 3 inside that struct is a ZERO_TAG, i.e. empty.
   * Reading only the struct therefore produced an empty text and the message was dropped.
   * Both levels are tried, struct first, because the self-built frames in the tests put the
   * text inside the struct.
   */
  fun decodeChat(frame: ByteArray): Pair<String, String>? {
    val outer = Tars.Reader(frame)
    val pushType = outer.readInt32(0)
    if (pushType != PUSH_TYPE_USER_MESSAGE) return null
    val inner = outer.readBytes(1)
    if (inner.isEmpty()) return null

    val middle = Tars.Reader(inner)
    // tag 0 carries the message group id; tag 1 is the message type. Both are small numbers
    // and arrive as BYTE as often as INT, which readInt32 already tolerates.
    middle.readInt32(0)
    val messageType = middle.readInt32(1)
    if (messageType != MESSAGE_TYPE_CHAT) return null
    val body = middle.readBytes(2)
    if (body.isEmpty()) return null

    var nick = ""
    var text = ""
    runCatching {
      Tars.Reader(body).readStruct(0) {
        nick = decodeText(readBytes(2))
        text = decodeText(readBytes(3))
      }
    }

    // The body-level tag 3 is where live frames actually carry the visible text.
    if (text.isBlank()) {
      val direct = runCatching { decodeText(Tars.Reader(body).readBytes(3)) }.getOrDefault("")
      if (direct.isNotBlank()) text = direct
    }
    if (nick.isBlank()) {
      val directNick = runCatching { decodeText(Tars.Reader(body).readBytes(2)) }.getOrDefault("")
      if (directNick.isNotBlank()) nick = directNick
    }

    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return nick.trim().ifBlank { "匿名" } to trimmed
  }

  /**
   * Huya is inconsistent about the charset: most messages are UTF-8 but a minority of
   * older clients still send GBK. Try UTF-8 first and fall back when it produces
   * replacement characters.
   */
  fun decodeText(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val utf8 = String(bytes, Charsets.UTF_8)
    if (!utf8.contains('\uFFFD')) return utf8
    val gbk = runCatching { String(bytes, charset("GBK")) }.getOrNull() ?: return utf8
    return if (gbk.count { it == '\uFFFD' } < utf8.count { it == '\uFFFD' }) gbk else utf8
  }

  /** Builds the register frame for [ayyuid]. */
  fun registerFrame(ayyuid: String): ByteArray = Tars.registerFrame(ayyuid)
}
