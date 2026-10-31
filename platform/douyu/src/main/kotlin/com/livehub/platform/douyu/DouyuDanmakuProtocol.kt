package com.livehub.platform.douyu

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Douyu danmaku wire format.
 *
 * Every frame is little-endian and looks like:
 *
 * ```
 * offset 0  int32  length   = payloadBytes + 9
 * offset 4  int32  length   (repeated)
 * offset 8  int16  type     = 689
 * offset 10 byte   0        (encryption, always 0)
 * offset 11 byte   0        (reserved, always 0)
 * offset 12 bytes  payload  (UTF-8)
 * offset n  byte   0        (NUL terminator, not counted in `length`)
 * ```
 *
 * Payloads use Douyu's `key@=value/` encoding where `/` separates fields, `@=` separates
 * key from value, and the escapes `@S` -> `/`, `@A` -> `@` are applied *inside* values.
 */
object DouyuDanmakuProtocol {
  const val MESSAGE_TYPE: Short = 689
  const val HEADER_SIZE = 12
  const val TRAILER_SIZE = 1
  const val HEARTBEAT_INTERVAL_MILLIS = 45_000L

  /** Encodes a text payload such as `type@=loginreq/roomid@=123/`. */
  fun encode(payload: String): ByteArray {
    val body = payload.toByteArray(Charsets.UTF_8)
    val buffer = ByteBuffer.allocate(HEADER_SIZE + body.size + TRAILER_SIZE)
      .order(ByteOrder.LITTLE_ENDIAN)
    val declared = body.size + 9
    buffer.putInt(declared)
    buffer.putInt(declared)
    buffer.putShort(MESSAGE_TYPE)
    buffer.put(0)
    buffer.put(0)
    buffer.put(body)
    buffer.put(0)
    return buffer.array()
  }

  /**
   * Decodes a binary frame into its text payload.
   *
   * Frames shorter than [HEADER_SIZE] + 1 are rejected; the trailing NUL and the
   * 12-byte header are stripped before UTF-8 decoding.
   */
  fun decode(frame: ByteArray): String? {
    if (frame.size < HEADER_SIZE + TRAILER_SIZE) return null
    var end = frame.size - TRAILER_SIZE
    // Some servers include the NUL in the declared length; be tolerant.
    while (end > HEADER_SIZE && frame[end - 1] == 0.toByte()) end--
    if (end <= HEADER_SIZE) return null
    return String(frame, HEADER_SIZE, end - HEADER_SIZE, Charsets.UTF_8)
  }

  /** Splits a payload into key/value pairs, applying the `@S` / `@A` escapes. */
  fun parseFields(payload: String): Map<String, String> {
    if (payload.isEmpty()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    payload.split('/').forEach { item ->
      if (item.isEmpty()) return@forEach
      val separator = item.indexOf("@=")
      if (separator <= 0) return@forEach
      val key = item.substring(0, separator)
      val rawValue = item.substring(separator + 2)
      result[key] = rawValue.replace("@S", "/").replace("@A", "@")
    }
    return result
  }

  fun loginRequest(roomId: String): String = "type@=loginreq/roomid@=$roomId/"

  fun joinGroup(roomId: String): String = "type@=joingroup/rid@=$roomId/gid@=1/"

  fun heartbeat(): String = "type@=mrkl/"

  /** Extracts a chat message, or null for any other message type. */
  fun parseChat(payload: String): Pair<String, String>? {
    val fields = parseFields(payload)
    if (fields["type"] != "chatmsg") return null
    val text = fields["txt"].orEmpty().trim()
    if (text.isEmpty()) return null
    val user = fields["nn"].orEmpty().trim().ifBlank { "匿名" }
    return user to text
  }

  /** Convenience for tests: joins frames back into one buffer. */
  fun concatenate(vararg frames: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    frames.forEach { out.write(it) }
    return out.toByteArray()
  }
}
