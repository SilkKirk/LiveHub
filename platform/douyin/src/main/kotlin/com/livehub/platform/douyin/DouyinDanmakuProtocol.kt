package com.livehub.platform.douyin

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/**
 * Douyin danmaku protobuf envelope decoding.
 *
 * Field numbers below are the ones the server actually uses, cross-checked against the
 * reference implementation's own lite codec (`DouyinProtoLite.kt`):
 *
 * ```
 * PushFrame
 *   2: uint64  logId
 *   6: string  payloadEncoding
 *   7: string  payloadType   // "msg" / "hb" / "ack"
 *   8: bytes   payload       // gzip(Response)
 *
 * Response
 *   1: repeated Message
 *
 * Message
 *   1: string  method        // "WebcastChatMessage" …
 *   2: bytes   payload
 *
 * ChatMessage
 *   2: User
 *   3: string  content
 *
 * User
 *   3: string  nickname
 * ```
 *
 * An earlier revision read the frame payload from field 3 and the chat text from field 7.
 * Neither exists in a real frame, so `decodeFrame` returned an empty list for **every**
 * push and Douyin chat never displayed a single message, no matter how healthy the socket
 * was.
 */
object DouyinDanmakuProtocol {
  const val METHOD_CHAT = "WebcastChatMessage"
  const val METHOD_MEMBER = "WebcastMemberMessage"
  const val METHOD_GIFT = "WebcastGiftMessage"

  const val HEARTBEAT_INTERVAL_MILLIS = 10_000L
  const val ACK_INTERVAL_MILLIS = 5_000L

  /** `PushFrame.payload`: gzip-compressed `Response`. */
  const val FIELD_PUSH_PAYLOAD = 8

  /** `PushFrame.logId`. */
  const val FIELD_LOG_ID = 2

  /** `PushFrame.payloadType`. */
  const val FIELD_PAYLOAD_TYPE = 7

  /** `PushFrame.payloadEncoding`. */
  const val FIELD_PAYLOAD_ENCODING = 6

  /** `PushFrame.internalExt`, the routing token echoed in an ack. */
  const val FIELD_INTERNAL_EXT = 5

  /** `ChatMessage.content`. */
  const val FIELD_CHAT_CONTENT = 3

  /** `ChatMessage.user`. */
  const val FIELD_CHAT_USER = 2

  /** `User.nickname`. */
  const val FIELD_USER_NICK = 3

  /** Decodes a PushFrame blob into `(method, ChatPayload)` pairs. */
  fun decodeFrame(frame: ByteArray): List<DecodedMessage> {
    val payload = DouyinProto.bytesField(frame, FIELD_PUSH_PAYLOAD) ?: return emptyList()
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
    val content = DouyinProto.stringField(body, FIELD_CHAT_CONTENT)?.trim().orEmpty()
    if (content.isEmpty()) return null
    val userBytes = DouyinProto.bytesField(body, FIELD_CHAT_USER)
    val nickname = userBytes?.let { DouyinProto.stringField(it, FIELD_USER_NICK) }?.trim().orEmpty()
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
   * Builds the websocket ack frame for [logId].
   *
   * Douyin closes the socket when acks stop, so the frame has to be a **PushFrame**:
   * `logId` in field 2, `payloadType = "ack"` in field 7, `internalExt` in field 8.
   * Writing them to fields 1 and 2 (the previous shape) produced a frame the server could
   * not parse, and it was sent unconditionally on a timer rather than in reply to a frame
   * that asked for it.
   */
  fun ackFrame(logId: Long, internalExt: String): ByteArray {
    val out = ByteArrayOutputStream()
    if (logId != 0L) {
      DouyinProto.writeVarint((FIELD_LOG_ID shl 3).toLong(), out)
      DouyinProto.writeVarint(logId, out)
    }
    DouyinProto.writeBytesField(
      FIELD_PAYLOAD_TYPE,
      "ack".toByteArray(Charsets.UTF_8),
      out,
    )
    if (internalExt.isNotEmpty()) {
      DouyinProto.writeBytesField(
        FIELD_PUSH_PAYLOAD,
        internalExt.toByteArray(Charsets.UTF_8),
        out,
      )
    }
    return out.toByteArray()
  }

  /**
   * Builds the heartbeat reply.
   *
   * `payloadType = "hb"` in field 7 with an empty payload in field 8; the room id does not
   * belong in the frame at all (the socket URL already scopes it).
   */
  fun heartbeatFrame(): ByteArray {
    val out = ByteArrayOutputStream()
    DouyinProto.writeBytesField(FIELD_PAYLOAD_TYPE, "hb".toByteArray(Charsets.UTF_8), out)
    return out.toByteArray()
  }

  /** Reads the `internalExt` routing token from a PushFrame. */
  fun internalExt(frame: ByteArray): String =
    DouyinProto.stringField(frame, FIELD_INTERNAL_EXT).orEmpty()

  /** Reads the heartbeat `logId` echoed by the server. */
  fun logId(frame: ByteArray): Long = DouyinProto.longField(frame, FIELD_LOG_ID) ?: 0L

  /** Reads `payloadType`; the server sends `"hb"` to request a heartbeat reply. */
  fun payloadType(frame: ByteArray): String =
    DouyinProto.stringField(frame, FIELD_PAYLOAD_TYPE).orEmpty()
}
