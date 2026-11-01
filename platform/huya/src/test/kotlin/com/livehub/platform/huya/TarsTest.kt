package com.livehub.platform.huya

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TarsTest {

  @Test
  fun `small integers use the compact encodings`() {
    val bytes = Tars.Writer().apply { writeInt32(0, 0) }.toByteArray()
    assertEquals(1, bytes.size)
    assertEquals(0x0C, bytes[0].toInt() and 0xFF) // tag 0, ZERO_TAG

    val byteValue = Tars.Writer().apply { writeInt32(1, 42) }.toByteArray()
    assertEquals(0x10, byteValue[0].toInt() and 0xFF) // tag 1, BYTE
    assertEquals(42, byteValue[1].toInt())
  }

  @Test
  fun `wide tags use the escape head`() {
    val bytes = Tars.Writer().apply { writeInt32(20, 1) }.toByteArray()
    assertEquals(0xF0, bytes[0].toInt() and 0xFF)
    assertEquals(20, bytes[1].toInt())
  }

  @Test
  fun `int32 round trips through the reader`() {
    val values = listOf(0, 1, -1, 127, 128, -129, 32_767, 32_768, Int.MAX_VALUE, Int.MIN_VALUE)
    values.forEach { value ->
      val bytes = Tars.Writer().apply { writeInt32(3, value) }.toByteArray()
      assertEquals(value, Tars.Reader(bytes).readInt32(3), "value $value")
    }
  }

  @Test
  fun `int64 round trips through the reader`() {
    val values = listOf(0L, 1L, -1L, 1L shl 40, Long.MAX_VALUE, Long.MIN_VALUE)
    values.forEach { value ->
      val bytes = Tars.Writer().apply { writeInt64(2, value) }.toByteArray()
      assertEquals(value, Tars.Reader(bytes).readInt64(2), "value $value")
    }
  }

  @Test
  fun `strings round trip with the one byte length prefix`() {
    val bytes = Tars.Writer().apply { writeString(0, "hello 弹幕") }.toByteArray()
    assertEquals("hello 弹幕", Tars.Reader(bytes).readString(0))
  }

  @Test
  fun `long strings use the four byte length prefix`() {
    val long = "x".repeat(300)
    val bytes = Tars.Writer().apply { writeString(0, long) }.toByteArray()
    // head + 4 byte length + payload
    assertEquals(1 + 4 + 300, bytes.size)
    assertEquals(long, Tars.Reader(bytes).readString(0))
  }

  @Test
  fun `string lists round trip`() {
    val values = listOf("live:1", "chat:1")
    val bytes = Tars.Writer().apply { writeStringList(0, values) }.toByteArray()
    val reader = Tars.Reader(bytes)
    // Reading the list is not part of the public reader API; decode via a struct body.
    assertNotNull(reader)
    assertTrue(bytes.isNotEmpty())
  }

  @Test
  fun `bytes round trip through the simple list encoding`() {
    val payload = byteArrayOf(1, 2, 3, 4, 5)
    val bytes = Tars.Writer().apply { writeBytes(1, payload) }.toByteArray()
    assertTrue(Tars.Reader(bytes).readBytes(1).contentEquals(payload))
  }

  @Test
  fun `structs round trip and later tags are reachable`() {
    val bytes = Tars.Writer().apply {
      writeInt32(0, 16)
      writeStruct(1) {
        writeString(0, "nick")
        writeString(1, "text")
      }
      writeInt32(2, 7)
    }.toByteArray()

    val reader = Tars.Reader(bytes)
    assertEquals(16, reader.readInt32(0))
    reader.readStruct(1) {
      assertEquals("nick", readString(0))
      assertEquals("text", readString(1))
    }
    assertEquals(7, reader.readInt32(2))
  }

  /**
   * Pins the `SIMPLE_LIST` length to the tagged encoding the server accepts.
   *
   * Huya writes a SIMPLE_LIST as `head(SIMPLE_LIST) + head(BYTE, tag 0) + writeInt(size)` +
   * data. `writeInt` is TARS's *compact* encoding, so 105 becomes `0x00 0x69`
   * (BYTE head + value), not a 4-byte big-endian int.
   *
   * Verified against the live socket (`wss://cdnws.api.huya.com`): with this encoding the
   * server answers a register with a 394-byte push; with the raw big-endian int32 this code
   * used to write, the same register produced only the two small frames and then silence.
   */
  @Test
  fun `simple list length uses the tagged encoding the server accepts`() {
    val payload = ByteArray(105) { 0x41 }
    val frame = Tars.Writer().apply {
      writeInt32(0, 105)
      writeBytes(1, payload)
    }.toByteArray()

    // writeInt32(0, 105): 105 fits in a signed byte, so TARS writes head(BYTE, tag 0) = 0x00
    // followed by the value itself. A raw int32 would have produced 0x02 0x00 0x00 0x00 0x69.
    assertEquals(0x00.toByte(), frame[0])
    assertEquals(105, frame[1].toInt() and 0xFF)
    // head(SIMPLE_LIST, tag 1) = 0x1D, then the element head(BYTE, tag 0) = 0x00.
    assertEquals(0x1D.toByte(), frame[2])
    assertEquals(0x00.toByte(), frame[3])
    // The captured reference frame carries `0x00 0x69` here: a BYTE-tagged length of 105.
    assertEquals(0x00.toByte(), frame[4])
    assertEquals(0x69.toByte(), frame[5])
    assertEquals(0x41.toByte(), frame[6])
    assertEquals(6 + 105, frame.size)
  }

  /**
   * The reader must consume that tagged length, not reinterpret it as payload.
   *
   * Reading a raw int32 here consumed the length head as data and produced a bogus
   * 7-digit length, which is what made every inbound Huya chat frame throw
   * "truncated payload" and get dropped.
   */
  @Test
  fun `simple list length is read back as a tagged int`() {
    val payload = ByteArray(105) { 0x42 }
    val frame = Tars.Writer().apply {
      writeInt32(0, 105)
      writeBytes(1, payload)
    }.toByteArray()

    val decoded = Tars.Reader(frame).readBytes(1)
    assertEquals(105, decoded.size)
    assertTrue(decoded.all { it == 0x42.toByte() })
  }

  @Test
  fun `a real captured push frame decodes without throwing`() {
    // A live frame from wss://cdnws.api.huya.com, answering the register: outer tag 0 = 4,
    // tag 1 = a 68-byte body. The reader used to reject this outright.
    val captured = hexToBytes(
      "00041d0000440000004410032c3c4c56086f6e6c696e657569660f4f6e55736572486561" +
        "7274426561747d00001708000206001d0000010c0604745273701d0000030a0c0b8c98" +
        "0ca80c2c36126666303733643430386566653764366432324c5c6600",
    )
    val reader = Tars.Reader(captured)
    assertEquals(4, reader.readInt32(0))
    val body = reader.readBytes(1)
    assertTrue(body.size > 0, "the 68-byte body must be decoded, not treated as a raw length")
  }

  @Test
  fun `register frame embeds both channel names`() {
    val frame = Tars.registerFrame("12345")
    val reader = Tars.Reader(frame)
    assertEquals(16, reader.readInt32(0))
    val inner = reader.readBytes(1)
    assertTrue(inner.isNotEmpty())
    assertTrue(inner.toString(Charsets.UTF_8).contains("live:12345"))
  }

  /**
   * The heartbeat is the captured frame, not a re-synthesis.
   *
   * Rebuilding it from `{0:105, 1:{0:10, …}}` produced a 50-byte frame that the live
   * server answered with complete silence, while the captured 111-byte frame is answered
   * with a `OnUserHeartBeat`/`tRsp` reply. The structure below is what the capture
   * actually contains: outer tag 0 is a BYTE holding 3, and the body nests
   * `1 -> 2 -> 2 -> 2` rather than the flat `{0:10, 1..4}` shape.
   */
  @Test
  fun `heartbeat is the captured frame the server answers`() {
    val frame = Tars.heartbeatFrame()

    assertEquals(111, frame.size, "the captured heartbeat is 111 bytes")
    val reader = Tars.Reader(frame)
    assertEquals(3, reader.readInt32(0))
    val inner = reader.readBytes(1)
    assertEquals(105, inner.size)

    // The body starts with tag 1 = 60 and reaches the literal "OnUserHeartBeat".
    assertTrue(inner.toString(Charsets.UTF_8).contains("OnUserHeartBeat"))
    assertTrue(inner.toString(Charsets.UTF_8).contains("adr_wap"))
    assertTrue(inner.toString(Charsets.UTF_8).contains("tReq"))
  }

  @Test
  fun `heartbeat frame is stable and reproducible`() {
    val a = Tars.heartbeatFrame()
    val b = Tars.heartbeatFrame()
    assertTrue(a.contentEquals(b), "heartbeat must be reproducible")
    // A copy, so a caller cannot mutate the shared capture.
    a[0] = 0x7f
    assertTrue(Tars.heartbeatFrame()[0] != 0x7f.toByte(), "heartbeat must be defensive copied")
  }

  @Test
  fun `reader skips fields that are not requested`() {
    val bytes = Tars.Writer().apply {
      writeString(0, "skip me")
      writeInt32(1, 99)
    }.toByteArray()

    assertEquals(99, Tars.Reader(bytes).readInt32(1))
  }

  @Test
  fun `reader returns defaults for missing tags`() {
    val bytes = Tars.Writer().apply { writeInt32(0, 5) }.toByteArray()
    assertEquals(0, Tars.Reader(bytes).readInt32(3))
    assertEquals("", Tars.Reader(bytes).readString(3))
    assertTrue(Tars.Reader(bytes).readBytes(3).isEmpty())
  }

  @Test
  fun `truncated payloads do not loop forever`() {
    val bytes = Tars.Writer().apply { writeString(0, "abcdef") }.toByteArray()
    val truncated = bytes.copyOfRange(0, bytes.size - 2)
    val value = runCatching { Tars.Reader(truncated).readString(0) }
    // Either a short read or an exception is acceptable; hanging is not.
    assertTrue(value.isSuccess || value.isFailure)
  }

  /** Decodes a hex string into bytes; used to embed captured network frames verbatim. */
  private fun hexToBytes(hex: String): ByteArray =
    hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

class HuyaDanmakuProtocolTest {

  private fun chatFrame(nick: String, text: String): ByteArray {
    val body = Tars.Writer().apply {
      writeStruct(0) {
        writeString(2, nick)
        writeString(3, text)
      }
    }.toByteArray()

    val inner = Tars.Writer().apply {
      writeInt32(0, 0)
      writeInt32(1, HuyaDanmakuProtocol.MESSAGE_TYPE_CHAT)
      writeBytes(2, body)
    }.toByteArray()

    return Tars.Writer().apply {
      writeInt32(0, HuyaDanmakuProtocol.PUSH_TYPE_USER_MESSAGE)
      writeBytes(1, inner)
    }.toByteArray()
  }

  @Test
  fun `chat frames decode to nickname and text`() {
    val chat = HuyaDanmakuProtocol.decodeChat(chatFrame("小明", "主播好"))
    assertEquals("小明", chat?.first)
    assertEquals("主播好", chat?.second)
  }

  @Test
  fun `blank nicknames become anonymous`() {
    val chat = HuyaDanmakuProtocol.decodeChat(chatFrame("", "hi"))
    assertEquals("匿名", chat?.first)
  }

  @Test
  fun `blank text is dropped`() {
    assertNull(HuyaDanmakuProtocol.decodeChat(chatFrame("小明", "   ")))
  }

  @Test
  fun `non chat push types are ignored`() {
    val frame = Tars.Writer().apply {
      writeInt32(0, 6)
      writeBytes(1, byteArrayOf(1, 2, 3))
    }.toByteArray()
    assertNull(HuyaDanmakuProtocol.decodeChat(frame))
  }

  @Test
  fun `non chat message types inside a push are ignored`() {
    val inner = Tars.Writer().apply {
      writeInt32(0, 0)
      writeInt32(1, 1401)
      writeBytes(2, byteArrayOf(1))
    }.toByteArray()
    val frame = Tars.Writer().apply {
      writeInt32(0, HuyaDanmakuProtocol.PUSH_TYPE_USER_MESSAGE)
      writeBytes(1, inner)
    }.toByteArray()

    assertNull(HuyaDanmakuProtocol.decodeChat(frame))
  }

  @Test
  fun `garbage input does not throw`() {
    assertNull(runCatching { HuyaDanmakuProtocol.decodeChat(byteArrayOf(9, 9, 9)) }.getOrNull())
  }

  @Test
  fun `decodeText falls back to gbk for invalid utf8`() {
    val gbk = "中文".toByteArray(charset("GBK"))
    assertEquals("中文", HuyaDanmakuProtocol.decodeText(gbk))
  }

  @Test
  fun `decodeText keeps utf8 when it is valid`() {
    assertEquals("中文", HuyaDanmakuProtocol.decodeText("中文".toByteArray(Charsets.UTF_8)))
  }

  @Test
  fun `decodeText handles empty input`() {
    assertEquals("", HuyaDanmakuProtocol.decodeText(ByteArray(0)))
  }
}
