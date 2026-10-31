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
  fun decodeChat(frame: ByteArray): Pair<String, String>? {
    val outer = Tars.Reader(frame)
    val pushType = outer.readInt32(0)
    if (pushType != PUSH_TYPE_USER_MESSAGE) return null
    val inner = outer.readBytes(1)
    if (inner.isEmpty()) return null

    val middle = Tars.Reader(inner)
    // tag 0 carries the message group id; tag 1 is the message type.
    middle.readInt32(0)
    val messageType = middle.readInt32(1)
    if (messageType != MESSAGE_TYPE_CHAT) return null
    val body = middle.readBytes(2)
    if (body.isEmpty()) return null

    var nick = ""
    var text = ""
    Tars.Reader(body).readStruct(0) {
      val nickBytes = readBytes(2)
      val textBytes = readBytes(3)
      nick = decodeText(nickBytes)
      text = decodeText(textBytes)
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
