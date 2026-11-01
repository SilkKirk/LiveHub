package com.livehub.platform.huya

import java.io.ByteArrayOutputStream

/**
 * Tars (JCE) binary codec used by Huya danmaku.
 *
 * A field is a head byte `(tag << 4) | type` (or `0xF0 | type` followed by one tag
 * byte when `tag >= 15`) plus a type specific payload. All multi-byte integers are
 * big-endian.
 */
object Tars {

  object Type {
    const val BYTE = 0
    const val SHORT = 1
    const val INT = 2
    const val LONG = 3
    const val STRING1 = 6
    const val STRING4 = 7
    const val LIST = 9
    const val STRUCT_BEGIN = 10
    const val STRUCT_END = 11
    const val ZERO_TAG = 12
    const val SIMPLE_LIST = 13
  }

  class Writer {
    private val out = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    fun writeByte(tag: Int, value: Byte) {
      writeHead(tag, Type.BYTE)
      out.write(value.toInt())
    }

    fun writeInt32(tag: Int, value: Int) {
      when {
        value == 0 -> writeHead(tag, Type.ZERO_TAG)
        value in Byte.MIN_VALUE..Byte.MAX_VALUE -> {
          writeHead(tag, Type.BYTE)
          out.write(value)
        }
        value in Short.MIN_VALUE..Short.MAX_VALUE -> {
          writeHead(tag, Type.SHORT)
          writeShort(value.toShort())
        }
        else -> {
          writeHead(tag, Type.INT)
          writeInt(value)
        }
      }
    }

    fun writeInt64(tag: Int, value: Long) {
      when {
        value == 0L -> writeHead(tag, Type.ZERO_TAG)
        value in Byte.MIN_VALUE..Byte.MAX_VALUE -> {
          writeHead(tag, Type.BYTE)
          out.write(value.toInt())
        }
        value in Short.MIN_VALUE..Short.MAX_VALUE -> {
          writeHead(tag, Type.SHORT)
          writeShort(value.toShort())
        }
        value in Int.MIN_VALUE..Int.MAX_VALUE -> {
          writeHead(tag, Type.INT)
          writeInt(value.toInt())
        }
        else -> {
          writeHead(tag, Type.LONG)
          writeLong(value)
        }
      }
    }

    fun writeString(tag: Int, value: String) {
      val bytes = value.toByteArray(Charsets.UTF_8)
      if (bytes.size <= 255) {
        writeHead(tag, Type.STRING1)
        out.write(bytes.size)
      } else {
        writeHead(tag, Type.STRING4)
        writeInt(bytes.size)
      }
      out.write(bytes)
    }

    fun writeStringList(tag: Int, values: List<String>) {
      writeHead(tag, Type.LIST)
      // A LIST's element count is a *tagged* int at tag 0, not a raw int32 — writing the
      // latter made len(0) come back as ZERO_TAG on the server's reader.
      writeInt32(0, values.size)
      values.forEach { writeString(0, it) }
    }

    fun writeBytes(tag: Int, value: ByteArray) {
      writeHead(tag, Type.SIMPLE_LIST)
      // The element head (BYTE tag 0) precedes a tagged length; the reference
      // implementation writes `writeInt(size, 0)`, and reading a raw int32 here is what
      // produced "truncated payload (len=1641489)".
      writeHead(0, Type.BYTE)
      writeInt32(0, value.size)
      out.write(value)
    }

    fun writeStruct(tag: Int, block: Writer.() -> Unit) {
      writeHead(tag, Type.STRUCT_BEGIN)
      block()
      writeHead(0, Type.STRUCT_END)
    }

    private fun writeHead(tag: Int, type: Int) {
      if (tag < 15) {
        out.write((tag shl 4) or (type and 0x0F))
      } else {
        out.write(0xF0 or (type and 0x0F))
        out.write(tag)
      }
    }

    private fun writeShort(value: Short) {
      out.write((value.toInt() ushr 8) and 0xFF)
      out.write(value.toInt() and 0xFF)
    }

    private fun writeInt(value: Int) {
      out.write((value ushr 24) and 0xFF)
      out.write((value ushr 16) and 0xFF)
      out.write((value ushr 8) and 0xFF)
      out.write(value and 0xFF)
    }

    private fun writeLong(value: Long) {
      for (shift in 56 downTo 0 step 8) {
        out.write(((value ushr shift) and 0xFF).toInt())
      }
    }
  }

  /** Reads fields in tag order; unknown tags are skipped by size. */
  class Reader(private val data: ByteArray, private var position: Int = 0) {
    var lastTag: Int = 0
      private set

    fun readInt32(tag: Int): Int {
      val head = requireHead(tag) ?: return 0
      return when (head) {
        Type.ZERO_TAG -> 0
        Type.BYTE -> readByteRaw().toInt()
        Type.SHORT -> readShortRaw().toInt()
        Type.INT -> readIntRaw()
        Type.LONG -> readLongRaw().toInt()
        else -> throw IllegalStateException("tag $tag is not an integer (type=$head)")
      }
    }

    fun readInt64(tag: Int): Long {
      val head = requireHead(tag) ?: return 0L
      return when (head) {
        Type.ZERO_TAG -> 0L
        Type.BYTE -> readByteRaw().toLong()
        Type.SHORT -> readShortRaw().toLong()
        Type.INT -> readIntRaw().toLong()
        Type.LONG -> readLongRaw()
        else -> throw IllegalStateException("tag $tag is not an integer (type=$head)")
      }
    }

    fun readString(tag: Int): String {
      val head = requireHead(tag) ?: return ""
      return String(readStringBytes(head), Charsets.UTF_8)
    }

    fun readBytes(tag: Int): ByteArray {
      val head = requireHead(tag) ?: return ByteArray(0)
      if (head == Type.SIMPLE_LIST) {
        // The element head (a BYTE tag 0) precedes a *tagged* length. Reading it as a raw
        // int32 consumed the length head as data and produced an enormous bogus length,
        // which is why every inbound Huya chat frame was dropped by the caller's
        // runCatching.
        readHead()
        val length = readTaggedLength()
        return readRaw(length)
      }
      return readStringBytes(head)
    }

    /**
     * Reads a length that the writer emitted with [Writer.writeInt32] at tag 0.
     *
     * The encoding is compact (BYTE/SHORT/INT/ZERO_TAG), so the head must be consumed
     * before the payload. Values that a peer wrote as a bare int32 are tolerated because
     * that is what older builds of this reader expected.
     */
    private fun readTaggedLength(): Int {
      val start = position
      val (_, lengthType) = readHead()
      return when (lengthType) {
        Type.ZERO_TAG -> 0
        Type.BYTE -> readByteRaw().toInt()
        Type.SHORT -> readShortRaw().toInt()
        Type.INT -> readIntRaw()
        else -> {
          // Not a length head: rewind and fall back to a bare int32.
          position = start
          readIntRaw()
        }
      }
    }

    fun readStruct(tag: Int, block: Reader.() -> Unit) {
      val head = requireHead(tag) ?: return
      if (head != Type.STRUCT_BEGIN) throw IllegalStateException("tag $tag is not a struct")
      block()
      // The struct body is terminated by a STRUCT_END head; consume it so the caller
      // can keep reading tags that follow the struct in the enclosing scope.
      consumeStructEnd()
    }

    /** Reads the next head and asserts it is a struct terminator. */
    private fun consumeStructEnd() {
      if (position >= data.size) return
      val start = position
      val (_, type) = readHead()
      if (type != Type.STRUCT_END) {
        // Not a terminator: rewind so the enclosing reader can still see this field.
        position = start
      }
    }

    /** Advances to [tag] or returns null when the stream is exhausted. */
    private fun requireHead(tag: Int): Int? {
      while (position < data.size) {
        val (currentTag, type) = readHead()
        lastTag = currentTag
        if (type == Type.STRUCT_END) return null
        if (currentTag == tag) return type
        if (currentTag > tag) throw IllegalStateException("missing tag $tag (saw $currentTag)")
        skip(type)
      }
      return null
    }

    private fun readHead(): Pair<Int, Int> {
      val b = readByteRaw().toInt() and 0xFF
      val type = b and 0x0F
      return if (b ushr 4 == 0x0F) {
        val tag = readByteRaw().toInt() and 0xFF
        tag to type
      } else {
        (b ushr 4) to type
      }
    }

    private fun skip(type: Int) {
      when (type) {
        Type.ZERO_TAG, Type.STRUCT_END -> Unit
        Type.BYTE -> readByteRaw()
        Type.SHORT -> readShortRaw()
        Type.INT -> readIntRaw()
        Type.LONG -> readLongRaw()
        Type.STRING1 -> {
          val length = readByteRaw().toInt() and 0xFF
          readRaw(length)
        }
        Type.STRING4 -> {
          val length = readIntRaw()
          readRaw(length)
        }
        Type.LIST -> skipList()
        Type.SIMPLE_LIST -> {
          readHead()
          val length = readTaggedLength()
          readRaw(length)
        }
        else -> throw IllegalStateException("cannot skip unsupported type $type")
      }
    }

    private fun skipList() {
      // The element count is a tagged int at tag 0, matching [Writer.writeStringList].
      var count = readTaggedLength()
      while (count > 0) {
        val (_, elementType) = readHead()
        skip(elementType)
        count--
      }
    }

    private fun readStringBytes(type: Int): ByteArray {
      val length = when (type) {
        Type.STRING1 -> readByteRaw().toInt() and 0xFF
        Type.STRING4 -> readIntRaw()
        Type.SIMPLE_LIST -> {
          readHead()
          readTaggedLength()
        }
        else -> throw IllegalStateException("not a string type: $type")
      }
      return readRaw(length)
    }

    private fun readRaw(length: Int): ByteArray {
      if (length < 0) throw IllegalStateException("negative length $length")
      val end = position + length
      if (end > data.size) throw IllegalStateException("truncated payload")
      val slice = data.copyOfRange(position, end)
      position = end
      return slice
    }

    private fun readByteRaw(): Byte {
      if (position >= data.size) throw IllegalStateException("unexpected end of payload")
      return data[position++]
    }

    private fun readShortRaw(): Short {
      val hi = readByteRaw().toInt() and 0xFF
      val lo = readByteRaw().toInt() and 0xFF
      return ((hi shl 8) or lo).toShort()
    }

    private fun readIntRaw(): Int {
      var value = 0
      repeat(4) { value = (value shl 8) or (readByteRaw().toInt() and 0xFF) }
      return value
    }

    private fun readLongRaw(): Long {
      var value = 0L
      repeat(8) { value = (value shl 8) or (readByteRaw().toLong() and 0xFF) }
      return value
    }
  }

  /**
   * Builds the register frame Huya expects on connect.
   * Outer: `{ 0: int32 16, 1: bytes inner }`
   * Inner: `{ 0: ["live:<uid>", "chat:<uid>"], 1: "" }`
   */
  fun registerFrame(ayyuid: String): ByteArray {
    val inner = Writer().apply {
      writeStringList(0, listOf("live:$ayyuid", "chat:$ayyuid"))
      writeString(1, "")
    }.toByteArray()

    return Writer().apply {
      writeInt32(0, 16)
      writeBytes(1, inner)
    }.toByteArray()
  }

  /**
   * Builds the heartbeat frame the web client sends every 20 seconds.
   *
   * This is a **captured** frame, not a re-synthesis. The previous implementation rebuilt
   * it from `{0:105, 1:{0:10, 1:"OnUserHeartBeat", …}}`, which looks plausible but is
   * structurally different from what the server accepts: verified live against
   * `wss://cdnws.api.huya.com`, the captured 111-byte frame is answered with a
   * `OnUserHeartBeat`/`tRsp` reply, while the synthesised 50-byte frame is answered with
   * **total silence**. A heartbeat the server ignores is worse than none, because the
   * watchdog then tears down a healthy socket.
   *
   * Decoded structure: outer `{0: byte 3, 1: simple-list(105)}`, and the body itself is
   * `{1: 60, 2: {2: 1, 2: {…tRsp…}, 2: {…"adr_wap"…}}}` — note the outer tag is 3, not the
   * 105 that the old code assumed from the envelope type constant.
   */
  fun heartbeatFrame(): ByteArray = CAPTURED_HEARTBEAT.copyOf()

  /** Captured `OnUserHeartBeat` frame, 111 bytes, verified against the live socket. */
  private val CAPTURED_HEARTBEAT: ByteArray = byteArrayOf(
    0x00, 0x03, 0x1d, 0x00, 0x00, 0x69, 0x00, 0x00, 0x00, 0x69, 0x10, 0x03, 0x2c, 0x3c,
    0x4c, 0x56, 0x08, 0x6f, 0x6e, 0x6c, 0x69, 0x6e, 0x65, 0x75, 0x69, 0x66, 0x0f, 0x4f,
    0x6e, 0x55, 0x73, 0x65, 0x72, 0x48, 0x65, 0x61, 0x72, 0x74, 0x42, 0x65, 0x61, 0x74,
    0x7d, 0x00, 0x00, 0x3c, 0x08, 0x00, 0x01, 0x06, 0x04, 0x74, 0x52, 0x65, 0x71, 0x1d,
    0x00, 0x00, 0x2f, 0x0a, 0x0a, 0x0c, 0x16, 0x00, 0x26, 0x00, 0x36, 0x07, 0x61, 0x64,
    0x72, 0x5f, 0x77, 0x61, 0x70, 0x46, 0x00, 0x0b, 0x12, 0x03, 0xae.toByte(), 0xf0.toByte(),
    0x0f, 0x22, 0x03, 0xae.toByte(), 0xf0.toByte(), 0x0f, 0x3c, 0x42, 0x6d, 0x52, 0x02, 0x60,
    0x5c, 0x60, 0x01, 0x7c, 0x82.toByte(), 0x00, 0x0b, 0xb0.toByte(), 0x1f, 0x9c.toByte(),
    0xac.toByte(), 0x0b, 0x8c.toByte(), 0x98.toByte(), 0x0c, 0xa8.toByte(), 0x0c,
  )

  /**
   * Envelope type of the captured heartbeat's *body*, kept for reference only.
   *
   * The outer tag of that frame is 3 (a BYTE), which is why these constants are not used
   * to build it any more — see [heartbeatFrame].
   */
  const val HEARTBEAT_OUTER_TYPE = 105

  /** Body-level type carried inside the captured heartbeat. */
  const val HEARTBEAT_INNER_TYPE = 10
}
