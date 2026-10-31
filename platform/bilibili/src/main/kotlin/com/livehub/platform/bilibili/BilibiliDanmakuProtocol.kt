package com.livehub.platform.bilibili

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Bilibili danmaku websocket framing.
 *
 * Every packet starts with a **16 byte big-endian** header:
 *
 * ```
 * 0..3   int32 packetLength (including header)
 * 4..5   int16 headerLength (16)
 * 6..7   int16 protocolVersion (1 = plain, 2 = zlib)
 * 8..11  int32 operation
 * 12..15 int32 sequence (always 1)
 * ```
 *
 * Operations: `2` heartbeat, `5` notification, `7` auth, `8` auth reply.
 * Version 2 bodies are zlib-compressed and contain several complete packets that must
 * be parsed recursively.
 */
object BilibiliDanmakuProtocol {
  const val HEADER_SIZE = 16
  const val OP_HEARTBEAT = 2
  const val OP_NOTIFICATION = 5
  const val OP_AUTH = 7
  const val OP_AUTH_REPLY = 8
  const val VER_PLAIN = 1
  const val VER_ZLIB = 2
  const val HEARTBEAT_INTERVAL_MILLIS = 30_000L

  const val WS_HOST_FALLBACK = "broadcastlv.chat.bilibili.com"
  const val WS_PORT_FALLBACK = 443

  data class Packet(val operation: Int, val version: Int, val body: ByteArray) {
    fun text(): String = String(body, Charsets.UTF_8)
  }

  fun encode(operation: Int, body: ByteArray = ByteArray(0), version: Int = VER_PLAIN): ByteArray {
    val buffer = ByteBuffer.allocate(HEADER_SIZE + body.size).order(ByteOrder.BIG_ENDIAN)
    buffer.putInt(HEADER_SIZE + body.size)
    buffer.putShort(HEADER_SIZE.toShort())
    buffer.putShort(version.toShort())
    buffer.putInt(operation)
    buffer.putInt(1)
    buffer.put(body)
    return buffer.array()
  }

  fun authFrame(uid: Long, roomId: String, token: String): ByteArray {
    val safeUid = if (uid < 0) 0 else uid
    val payload = """{"uid":$safeUid,"roomid":$roomId,"protover":1,"platform":"web","type":2,"key":"$token"}"""
    return encode(OP_AUTH, payload.toByteArray(Charsets.UTF_8))
  }

  fun heartbeatFrame(): ByteArray = encode(OP_HEARTBEAT)

  /**
   * Splits a websocket frame into logical packets, transparently inflating zlib
   * versions. Malformed packets stop parsing rather than throwing: the connection is
   * long lived and one bad frame must not kill it.
   */
  fun decode(frame: ByteArray): List<Packet> = decodeInternal(frame, depth = 0)

  private fun decodeInternal(frame: ByteArray, depth: Int): List<Packet> {
    if (depth > MAX_DEPTH || frame.size < HEADER_SIZE) return emptyList()
    val packets = mutableListOf<Packet>()
    val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
    while (buffer.remaining() >= HEADER_SIZE) {
      val start = buffer.position()
      val length = buffer.int
      if (length < HEADER_SIZE || length > buffer.limit() - start) break
      buffer.short // header length
      val version = buffer.short.toInt()
      val operation = buffer.int
      buffer.int // sequence
      val bodyLength = length - HEADER_SIZE
      if (bodyLength < 0) break
      val body = ByteArray(bodyLength)
      buffer.get(body)

      if (version == VER_ZLIB) {
        val inflated = inflate(body) ?: continue
        packets += decodeInternal(inflated, depth + 1)
      } else {
        packets += Packet(operation = operation, version = version, body = body)
      }
    }
    return packets
  }

  private fun inflate(data: ByteArray): ByteArray? {
    if (data.isEmpty()) return null
    val inflater = Inflater()
    return try {
      inflater.setInput(data)
      val out = ByteArrayOutputStream(data.size * 4)
      val chunk = ByteArray(8 * 1024)
      while (!inflater.finished()) {
        val read = inflater.inflate(chunk)
        if (read == 0) {
          if (inflater.needsInput() || inflater.needsDictionary()) break
        } else {
          out.write(chunk, 0, read)
        }
      }
      out.toByteArray()
    } catch (error: Exception) {
      null
    } finally {
      inflater.end()
    }
  }

  /** Extracts `(nickname, text)` from a `DANMU_MSG` notification. */
  fun parseDanmaku(packet: Packet): Pair<String, String>? {
    if (packet.operation != OP_NOTIFICATION) return null
    val json = runCatching {
      kotlinx.serialization.json.Json.parseToJsonElement(packet.text())
        .let { it as? kotlinx.serialization.json.JsonObject }
    }.getOrNull() ?: return null
    val cmd = (json["cmd"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe()
      ?: return null
    if (!cmd.startsWith("DANMU_MSG")) return null
    val info = json["info"] as? kotlinx.serialization.json.JsonArray ?: return null
    val text = (info.getOrNull(1) as? kotlinx.serialization.json.JsonPrimitive)
      ?.contentOrNullSafe()?.trim().orEmpty()
    if (text.isEmpty()) return null
    val userArray = info.getOrNull(2) as? kotlinx.serialization.json.JsonArray
    val nick = (userArray?.getOrNull(1) as? kotlinx.serialization.json.JsonPrimitive)
      ?.contentOrNullSafe()?.trim().orEmpty()
    return nick.ifBlank { "匿名" } to text
  }

  private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()

  private const val MAX_DEPTH = 4

  /** Host list entry returned by `getDanmuInfo`. */
  data class Endpoint(val host: String, val wssPort: Int) {
    val url: String
      get() = if (wssPort == 443) "wss://$host/sub" else "wss://$host:$wssPort/sub"
  }

  /** Deduplicates and orders endpoints, preferring the default 443 port. */
  fun orderEndpoints(endpoints: List<Endpoint>): List<Endpoint> {
    if (endpoints.isEmpty()) {
      return listOf(Endpoint(WS_HOST_FALLBACK, WS_PORT_FALLBACK))
    }
    return endpoints
      .distinctBy { "${it.host}:${it.wssPort}" }
      .sortedWith(compareBy({ if (it.wssPort == 443) 0 else 1 }, { it.host }))
  }
}
