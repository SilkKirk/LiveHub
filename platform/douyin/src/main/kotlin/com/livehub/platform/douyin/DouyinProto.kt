package com.livehub.platform.douyin

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf reader for Douyin's danmaku payloads.
 *
 * Douyin pushes `PushFrame` envelopes containing a `Response` whose `messages[]` are
 * gzip-compressed `Message` blobs. Reimplementing just the wire format avoids a
 * generated-code build step while staying independent of `webmssdk`.
 */
object DouyinProto {

  /** One decoded protobuf field: tag number, wire type and raw payload. */
  data class Field(val number: Int, val wireType: Int, val raw: ByteArray) {
    fun asString(): String = String(raw, Charsets.UTF_8)

    fun asLong(): Long = raw.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }

    fun asBool(): Boolean = asLong() != 0L

    fun asBytes(): ByteArray = raw
  }

  /**
   * Parses [data] into its fields. Only wire types 0 (varint), 1 (64 bit), 2 (length
   * delimited) and 5 (32 bit) are supported, which covers the fields LiveHub needs.
   */
  fun parse(data: ByteArray): List<Field> {
    val fields = mutableListOf<Field>()
    var index = 0
    while (index < data.size) {
      val key = readVarint(data, index) ?: break
      index = key.second
      val number = (key.first ushr 3).toInt()
      val wireType = (key.first and 0x07L).toInt()
      when (wireType) {
        0 -> {
          val value = readVarint(data, index) ?: break
          index = value.second
          fields += Field(number, wireType, encodeVarint(value.first))
        }
        1 -> {
          if (index + 8 > data.size) break
          fields += Field(number, wireType, data.copyOfRange(index, index + 8))
          index += 8
        }
        2 -> {
          val length = readVarint(data, index) ?: break
          index = length.second
          val size = length.first.toInt()
          if (size < 0 || index + size > data.size) break
          fields += Field(number, wireType, data.copyOfRange(index, index + size))
          index += size
        }
        5 -> {
          if (index + 4 > data.size) break
          fields += Field(number, wireType, data.copyOfRange(index, index + 4))
          index += 4
        }
        else -> break
      }
    }
    return fields
  }

  /** Convenience: the first field with [number] and length-delimited payload. */
  fun bytesField(data: ByteArray, number: Int): ByteArray? =
    parse(data).firstOrNull { it.number == number && it.wireType == 2 }?.raw

  fun stringField(data: ByteArray, number: Int): String? =
    bytesField(data, number)?.toString(Charsets.UTF_8)

  fun longField(data: ByteArray, number: Int): Long? =
    parse(data).firstOrNull { it.number == number }?.asLong()

  /** Writes a field header plus a length-delimited payload. */
  fun writeBytesField(number: Int, payload: ByteArray, out: ByteArrayOutputStream) {
    writeVarint(((number shl 3) or 2).toLong(), out)
    writeVarint(payload.size.toLong(), out)
    out.write(payload)
  }

  fun writeVarint(value: Long, out: ByteArrayOutputStream) {
    var v = value
    while (true) {
      if (v and 0x7FL.inv() == 0L) {
        out.write(v.toInt())
        return
      }
      out.write(((v and 0x7F) or 0x80).toInt())
      v = v ushr 7
    }
  }

  fun encodeVarint(value: Long): ByteArray {
    val out = ByteArrayOutputStream()
    writeVarint(value, out)
    return out.toByteArray()
  }

  private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
    var result = 0L
    var shift = 0
    var index = start
    while (index < data.size) {
      val byte = data[index].toInt() and 0xFF
      result = result or ((byte and 0x7F).toLong() shl shift)
      index++
      if (byte and 0x80 == 0) return result to index
      shift += 7
      if (shift > 63) return null
    }
    return null
  }
}
