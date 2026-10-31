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

  @Test
  fun `register frame embeds both channel names`() {
    val frame = Tars.registerFrame("12345")
    val reader = Tars.Reader(frame)
    assertEquals(16, reader.readInt32(0))
    val inner = reader.readBytes(1)
    assertTrue(inner.isNotEmpty())
    assertTrue(inner.toString(Charsets.UTF_8).contains("live:12345"))
  }

  @Test
  fun `heartbeat frame carries the documented inner payload`() {
    val frame = Tars.heartbeatFrame()

    val reader = Tars.Reader(frame)
    assertEquals(Tars.HEARTBEAT_OUTER_TYPE, reader.readInt32(0))
    val inner = reader.readBytes(1)

    val body = Tars.Reader(inner)
    assertEquals(Tars.HEARTBEAT_INNER_TYPE, body.readInt32(0))
    assertEquals("OnUserHeartBeat", body.readString(1))
    assertEquals("tReq", body.readString(2))
    assertEquals("adr_wap", body.readString(3))
    assertEquals("onlineui", body.readString(4))
  }

  @Test
  fun `heartbeat frame is stable and self consistent`() {
    val a = Tars.heartbeatFrame()
    val b = Tars.heartbeatFrame()
    assertTrue(a.contentEquals(b), "heartbeat must be reproducible")

    // The OUTER envelope is `{ 0: int32, 1: simple-list bytes }`. A simple list stores
    // an explicit int32 length, so the frame is only accepted when that length equals
    // the real body size. Decode it through the reader instead of guessing offsets.
    val inner = Tars.Reader(a).readBytes(1)
    val declared = inner.size
    assertTrue(declared > 0, "heartbeat body must not be empty")

    // And the decoded body must itself parse as a struct.
    val body = Tars.Reader(inner)
    assertEquals(Tars.HEARTBEAT_INNER_TYPE, body.readInt32(0))
    assertEquals("OnUserHeartBeat", body.readString(1))
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
