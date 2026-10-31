package com.livehub.platform.douyin

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/**
 * Douyin danmaku protobuf envelope decoding.
 *
 * Server frames are `PushFrame` messages:
 *
 * ```
 * 1: string    payloadType   // "msg"
 * 2: uint64    logId
 * 3: bytes     payload       // gzip(Response)
 * 4: uint64    payloadType2
 * ```
 *
 * `Response` carries `messages[]` (field 1), each holding a `method` (field 1) and a
 * gzip-compressed `payload` (field 2). Chat messages have `method == "WebcastChatMessage"`
 * and their payload is a `ChatMessage` with `content` (field 7) and a `User`
 * (field 2, whose `nickname` is field 3).
 */
object DouyinDanmakuProtocol {
  const val METHOD_CHAT = "WebcastChatMessage"
  const val METHOD_MEMBER = "WebcastMemberMessage"
  const val METHOD_GIFT = "WebcastGiftMessage"

  const val HEARTBEAT_INTERVAL_MILLIS = 10_000L
  const val ACK_INTERVAL_MILLIS = 5_000L

  /** Decodes a PushFrame blob into `(method, ChatPayload)` pairs. */
  fun decodeFrame(frame: ByteArray): List<DecodedMessage> {
    val payload = DouyinProto.bytesField(frame, 3) ?: return emptyList()
    val response = decompress(payload) ?: return emptyList()
    val messages = DouyinProto.parse(response).filter {
      it.number == 1 && it.wireType == 2
    }
    return messages.mapNotNull { field -> decodeMessage(field.raw) }
  }

  private fun decodeMessage(raw: ByteArray): DecodedMessage? {
    val method = DouyinProto.stringField(raw, 1) ?: return null
    val payload = DouyinProto.bytesField(raw, 2) ?: return null
    val body = decompress(payload) ?: payload
    return DecodedMessage(method = method, body = body)
  }

  /** Extracts `(nickname, text)` from a `ChatMessage` payload. */
  fun parseChat(body: ByteArray): Pair<String, String>? {
    val content = DouyinProto.stringField(body, 7)?.trim().orEmpty()
    if (content.isEmpty()) return null
    val userBytes = DouyinProto.bytesField(body, 2)
    val nickname = userBytes?.let { DouyinProto.stringField(it, 3) }?.trim().orEmpty()
    return nickname.ifBlank { "匿名" } to content
  }

  /** Extracts `(nickname, text)` for membership ("xxx 来了") messages. */
  fun parseMember(body: ByteArray): Pair<String, String>? {
    val userBytes = DouyinProto.bytesField(body, 2) ?: return null
    val nickname = DouyinProto.stringField(userBytes, 3)?.trim().orEmpty()
    if (nickname.isEmpty()) return null
    return nickname to "来了"
  }

  data class DecodedMessage(val method: String, val body: ByteArray)

  /** Gzip, then zlib, then raw — Douyin has used all three over time. */
  fun decompress(data: ByteArray): ByteArray? {
    if (data.size < 2) return null
    gzip(data)?.let { return it }
    zlib(data)?.let { return it }
    return null
  }

  private fun gzip(data: ByteArray): ByteArray? {
    if (data.size < 2) return null
    val magic = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
    if (magic != 0x1F8B) return null
    return runCatching {
      GZIPInputStream(data.inputStream()).use { it.readBytes() }
    }.getOrNull()
  }

  private fun zlib(data: ByteArray): ByteArray? {
    if (data.size < 2) return null
    return runCatching {
      val inflater = Inflater()
      try {
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4)
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
          val read = inflater.inflate(buffer)
          if (read == 0) break
          out.write(buffer, 0, read)
        }
        out.toByteArray()
      } finally {
        inflater.end()
      }
    }.getOrNull()
  }

  /**
   * Builds the websocket ack frame for [logId]; Douyin closes the socket when acks stop.
   */
  fun ackFrame(logId: Long, internalExt: String): ByteArray {
    val out = ByteArrayOutputStream()
    DouyinProto.writeVarint((1 shl 3).toLong(), out)
    DouyinProto.writeVarint(logId, out)
    if (internalExt.isNotEmpty()) {
      DouyinProto.writeBytesField(2, internalExt.toByteArray(Charsets.UTF_8), out)
    }
    return out.toByteArray()
  }

  /** Reads the `internalExt` routing token from a PushFrame. */
  fun internalExt(frame: ByteArray): String = DouyinProto.stringField(frame, 5).orEmpty()

  /** Reads the heartbeat `logId` echoed by the server. */
  fun logId(frame: ByteArray): Long = DouyinProto.longField(frame, 2) ?: 0L

  /** Reads `payloadType`; the server sends `"hb"` to request a heartbeat reply. */
  fun payloadType(frame: ByteArray): String = DouyinProto.stringField(frame, 7).orEmpty()
}
