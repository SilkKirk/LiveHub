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
      // User.nickname is field 3.
      DouyinProto.writeBytesField(DouyinDanmakuProtocol.FIELD_USER_NICK, nick.toByteArray(), out)
    }.toByteArray()

    return ByteArrayOutputStream().also { out ->
      // ChatMessage.user is field 2 and ChatMessage.content is field 3 — content is *not*
      // field 7, which is where an earlier revision looked and why nothing ever decoded.
      DouyinProto.writeBytesField(DouyinDanmakuProtocol.FIELD_CHAT_USER, user, out)
      DouyinProto.writeBytesField(DouyinDanmakuProtocol.FIELD_CHAT_CONTENT, text.toByteArray(), out)
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
      // PushFrame: payloadType field 7, logId field 2, payload field 8.
      DouyinProto.writeBytesField(
        DouyinDanmakuProtocol.FIELD_PAYLOAD_TYPE,
        "msg".toByteArray(),
        out,
      )
      DouyinProto.writeVarint((DouyinDanmakuProtocol.FIELD_LOG_ID.toLong() shl 3) or 0, out)
      DouyinProto.writeVarint(logId, out)
      DouyinProto.writeBytesField(
        DouyinDanmakuProtocol.FIELD_PUSH_PAYLOAD,
        gzip(response),
        out,
      )
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
      DouyinProto.writeBytesField(
        DouyinDanmakuProtocol.FIELD_PAYLOAD_TYPE,
        "hb".toByteArray(),
        out,
      )
    }.toByteArray()
    assertEquals("hb", DouyinDanmakuProtocol.payloadType(hb))
  }

  /**
   * The ack must be a `PushFrame`: logId in field 2, `payloadType = "ack"` in field 7 and
   * the routing token in field 8.
   *
   * The previous shape wrote logId to field 1 and the token to field 2, so the server could
   * not recognise the frame at all. Field 1 does not exist on a PushFrame.
   */
  @Test
  fun `ack frame is a push frame with the log id in field 2`() {
    val ack = DouyinDanmakuProtocol.ackFrame(logId = 5, internalExt = "")
    assertEquals(5L, DouyinProto.longField(ack, DouyinDanmakuProtocol.FIELD_LOG_ID))
    assertEquals("ack", DouyinDanmakuProtocol.payloadType(ack))
    assertNull(DouyinProto.longField(ack, 1), "field 1 is not a PushFrame field")
  }

  @Test
  fun `ack frame carries the routing token in the payload field`() {
    val ack = DouyinDanmakuProtocol.ackFrame(logId = 5, internalExt = "ext")
    assertEquals(
      "ext",
      DouyinProto.stringField(ack, DouyinDanmakuProtocol.FIELD_PUSH_PAYLOAD),
    )
  }

  @Test
  fun `heartbeat frame declares the hb payload type and no room id`() {
    val hb = DouyinDanmakuProtocol.heartbeatFrame()
    assertEquals("hb", DouyinDanmakuProtocol.payloadType(hb))
    // The room is scoped by the socket URL; leaking it into the frame is wrong.
    assertNull(DouyinProto.stringField(hb, 2))
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

/**
 * Tests for the one `a_bogus` implementation the module ships.
 *
 * The previous suite asserted on a locally invented `xBogus`/`aBogus` pair that was not the
 * algorithm Douyin uses, so it could pass while every signed request was rejected. Those
 * functions are deleted; these tests cover the real transcription in [DouyinABogus] and the
 * query assembly in [DouyinSigner].
 */
class DouyinSignerTest {

  @Test
  fun `a-bogus is deterministic for the same query and ua`() {
    val a = DouyinABogus.aBogus("aid=6383&count=20", DouyinSigner.USER_AGENT)
    val b = DouyinABogus.aBogus("aid=6383&count=20", DouyinSigner.USER_AGENT)
    assertEquals(a, b)
    assertTrue(a.isNotEmpty())
  }

  @Test
  fun `a-bogus changes with the query`() {
    val a = DouyinABogus.aBogus("aid=6383&count=20", DouyinSigner.USER_AGENT)
    val b = DouyinABogus.aBogus("aid=6383&count=21", DouyinSigner.USER_AGENT)
    assertTrue(a != b, "a different query must produce a different signature")
  }

  @Test
  fun `a-bogus is url safe`() {
    val value = DouyinABogus.aBogus("aid=6383&count=20", DouyinSigner.USER_AGENT)
    assertTrue(value.isNotEmpty())
    assertTrue(value.none { it == '+' || it == '/' || it == '=' }, "value was $value")
    assertTrue(
      value.all { it.isLetterOrDigit() || it == '-' || it == '_' },
      "value was $value",
    )
  }

  /** The signature is parameter-order sensitive, which is why callers must not sort. */
  @Test
  fun `a-bogus depends on parameter order`() {
    val a = DouyinABogus.aBogus("aid=6383&count=20", DouyinSigner.USER_AGENT)
    val b = DouyinABogus.aBogus("count=20&aid=6383", DouyinSigner.USER_AGENT)
    assertTrue(a != b, "reordering the query must change the signature")
  }

  @Test
  fun `signQuery appends a_bogus and preserves the parameter order`() {
    val query = DouyinSigner.signQuery(listOf("aid" to "6383", "count" to "20"))

    assertTrue(query.startsWith("aid=6383&count=20&"), "order changed: $query")
    assertTrue(query.contains("&a_bogus="), "missing signature: $query")
    assertTrue(!query.contains("X-Bogus"), "the invented X-Bogus must not be sent")
  }

  @Test
  fun `signQuery percent-encodes values`() {
    val query = DouyinSigner.signQuery(listOf("keyword" to "英雄 联盟"))
    assertTrue(query.startsWith("keyword=%E8%8B%B1%E9%9B%84%20%E8%81%94%E7%9B%9F"), query)
  }

  @Test
  fun `the default msToken has the documented length and alphabet`() {
    val token = DouyinSigner.randomMsToken()
    assertEquals(DouyinSigner.MS_TOKEN_LENGTH, token.length)
    assertTrue(token.all { it.isLetterOrDigit() }, "unexpected characters: $token")
    assertTrue(token != DouyinSigner.randomMsToken(), "tokens must not repeat")
  }

  @Test
  fun `the default cookie carries ttwid`() {
    assertTrue(DouyinSigner.DEFAULT_COOKIE.startsWith("ttwid="))
  }
}
