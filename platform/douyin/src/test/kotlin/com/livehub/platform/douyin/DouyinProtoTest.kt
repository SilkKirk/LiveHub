package com.livehub.platform.douyin

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class DouyinProtoTest {

  @Test
  fun `writeVarint emits little endian base 128 groups`() {
    val out = ByteArrayOutputStream()
    DouyinProto.writeVarint(300, out)
    assertArrayEquals(byteArrayOf(0xAC.toByte(), 0x02), out.toByteArray())

    val single = ByteArrayOutputStream()
    DouyinProto.writeVarint(1, single)
    assertArrayEquals(byteArrayOf(0x01), single.toByteArray())
  }

  @Test
  fun `bytes fields round trip`() {
    val payload = "hello".toByteArray()
    val out = ByteArrayOutputStream()
    DouyinProto.writeBytesField(3, payload, out)

    assertEquals("hello", DouyinProto.stringField(out.toByteArray(), 3))
  }

  @Test
  fun `parse returns every field in order`() {
    val out = ByteArrayOutputStream()
    DouyinProto.writeBytesField(1, "a".toByteArray(), out)
    DouyinProto.writeBytesField(2, "b".toByteArray(), out)
    DouyinProto.writeVarint((5L shl 3) or 0, out)
    DouyinProto.writeVarint(42, out)

    val fields = DouyinProto.parse(out.toByteArray())
    assertEquals(3, fields.size)
    assertEquals(1, fields[0].number)
    assertEquals("a", fields[0].asString())
    assertEquals(2, fields[1].number)
    assertEquals(42L, fields[2].asLong())
  }

  @Test
  fun `parse tolerates truncated input`() {
    assertTrue(DouyinProto.parse(byteArrayOf(0x0A)).isEmpty())
    assertTrue(DouyinProto.parse(ByteArray(0)).isEmpty())
  }

  @Test
  fun `missing fields return null`() {
    assertNull(DouyinProto.stringField(ByteArray(0), 1))
    assertNull(DouyinProto.bytesField(ByteArray(0), 9))
    assertNull(DouyinProto.longField(ByteArray(0), 2))
  }
}

class DouyinDanmakuProtocolTest {

  private fun gzip(data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    java.util.zip.GZIPOutputStream(out).use { it.write(data) }
    return out.toByteArray()
  }

  private fun chatMessage(nick: String, text: String): ByteArray {
    val user = ByteArrayOutputStream().also { out ->
      DouyinProto.writeBytesField(3, nick.toByteArray(), out)
    }.toByteArray()

    return ByteArrayOutputStream().also { out ->
      DouyinProto.writeBytesField(2, user, out)
      DouyinProto.writeBytesField(7, text.toByteArray(), out)
    }.toByteArray()
  }

  private fun wrap(method: String, body: ByteArray): ByteArray =
    ByteArrayOutputStream().also { out ->
      DouyinProto.writeBytesField(1, method.toByteArray(), out)
      DouyinProto.writeBytesField(2, gzip(body), out)
    }.toByteArray()

  private fun frame(messages: List<ByteArray>, logId: Long = 7): ByteArray {
    val response = ByteArrayOutputStream().also { out ->
      messages.forEach { DouyinProto.writeBytesField(1, it, out) }
    }.toByteArray()

    return ByteArrayOutputStream().also { out ->
      DouyinProto.writeBytesField(1, "msg".toByteArray(), out)
      DouyinProto.writeVarint((2L shl 3) or 0, out)
      DouyinProto.writeVarint(logId, out)
      DouyinProto.writeBytesField(3, gzip(response), out)
    }.toByteArray()
  }

  @Test
  fun `chat frames decode to nickname and text`() {
    val decoded = DouyinDanmakuProtocol.decodeFrame(
      frame(listOf(wrap(DouyinDanmakuProtocol.METHOD_CHAT, chatMessage("小明", "主播好")))),
    )

    assertEquals(1, decoded.size)
    assertEquals(DouyinDanmakuProtocol.METHOD_CHAT, decoded.first().method)
    val chat = DouyinDanmakuProtocol.parseChat(decoded.first().body)
    assertEquals("小明", chat?.first)
    assertEquals("主播好", chat?.second)
  }

  @Test
  fun `blank nicknames become anonymous`() {
    val chat = DouyinDanmakuProtocol.parseChat(chatMessage("", "hi"))
    assertEquals("匿名", chat?.first)
  }

  @Test
  fun `blank text is dropped`() {
    assertNull(DouyinDanmakuProtocol.parseChat(chatMessage("小明", "   ")))
  }

  @Test
  fun `membership messages decode to a joined notice`() {
    val chat = DouyinDanmakuProtocol.parseMember(chatMessage("小明", "ignored"))
    assertEquals("小明", chat?.first)
    assertEquals("来了", chat?.second)
  }

  @Test
  fun `membership without a nickname is dropped`() {
    assertNull(DouyinDanmakuProtocol.parseMember(chatMessage("", "")))
  }

  @Test
  fun `logId is read from the push frame`() {
    assertEquals(7L, DouyinDanmakuProtocol.logId(frame(emptyList(), logId = 7)))
    // 123456 encodes as a multi byte varint; the decoder must not treat it as raw bytes.
    assertEquals(123_456L, DouyinDanmakuProtocol.logId(frame(emptyList(), logId = 123_456)))
    assertEquals(300L, DouyinDanmakuProtocol.logId(frame(emptyList(), logId = 300)))
  }

  @Test
  fun `payload type is read from the push frame`() {
    val hb = ByteArrayOutputStream().also { out ->
      DouyinProto.writeBytesField(7, "hb".toByteArray(), out)
    }.toByteArray()
    assertEquals("hb", DouyinDanmakuProtocol.payloadType(hb))
  }

  @Test
  fun `ack frame echoes the log id`() {
    val ack = DouyinDanmakuProtocol.ackFrame(logId = 5, internalExt = "")
    assertEquals(5L, DouyinProto.longField(ack, 1))
  }

  @Test
  fun `ack frame carries the routing token when provided`() {
    val ack = DouyinDanmakuProtocol.ackFrame(logId = 5, internalExt = "ext")
    assertEquals("ext", DouyinProto.stringField(ack, 2))
  }

  @Test
  fun `garbage frames decode to an empty list`() {
    assertTrue(DouyinDanmakuProtocol.decodeFrame(byteArrayOf(1, 2, 3)).isEmpty())
    assertTrue(DouyinDanmakuProtocol.decodeFrame(ByteArray(0)).isEmpty())
  }

  @Test
  fun `decompress handles gzip and corrupt input`() {
    assertNotNull(DouyinDanmakuProtocol.decompress(gzip("x".toByteArray())))
    assertNull(DouyinDanmakuProtocol.decompress(byteArrayOf(0, 1)))
  }
}

class DouyinSignerTest {

  @Test
  fun `x-bogus is deterministic for the same seed`() {
    val a = DouyinSigner.xBogus("a=1&b=2", "ua", timestamp = 1_700_000_000, randomSeed = 7)
    val b = DouyinSigner.xBogus("a=1&b=2", "ua", timestamp = 1_700_000_000, randomSeed = 7)
    assertEquals(a, b)
    assertTrue(a.isNotEmpty())
  }

  @Test
  fun `x-bogus changes with the query`() {
    val a = DouyinSigner.xBogus("a=1", "ua", 1_700_000_000, 7)
    val b = DouyinSigner.xBogus("a=2", "ua", 1_700_000_000, 7)
    assertTrue(a != b)
  }

  @Test
  fun `a-bogus is url safe`() {
    val value = DouyinSigner.aBogus("aid=6383&count=20", "ua", 1_700_000_000, 7)
    assertTrue(value.isNotEmpty())
    assertTrue(value.none { it == '+' || it == '/' || it == '=' }, "value was $value")
    assertTrue(value.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "value was $value")
  }

  @Test
  fun `a-bogus does not overflow on any hex digest prefix`() {
    // Regression: parsing an 8 char md5 prefix with toInt(16) overflowed for any value
    // above 0x7FFFFFFF. Every seed must now be accepted.
    repeat(64) { seed ->
      val value = DouyinSigner.aBogus("q=$seed", "ua", 1_700_000_000L + seed, seed)
      assertTrue(value.isNotEmpty(), "seed $seed produced an empty signature")
    }
  }

  @Test
  fun `sign appends both signature parameters and preserves order`() {
    val signed = DouyinSigner.sign(
      orderedParams = listOf("aid" to "6383", "count" to "20"),
      userAgent = "ua",
      timestamp = 1_700_000_000_000,
    )

    val keys = signed.params.keys.toList()
    assertEquals(listOf("aid", "count", "X-Bogus", "a_bogus"), keys)
    assertTrue(signed.queryString().contains("aid=6383"))
  }
}
