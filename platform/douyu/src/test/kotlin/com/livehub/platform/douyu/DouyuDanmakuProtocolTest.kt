package com.livehub.platform.douyu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DouyuDanmakuProtocolTest {

  @Test
  fun `login request carries the room id`() {
    assertEquals("type@=loginreq/roomid@=12345/", DouyuDanmakuProtocol.loginRequest("12345"))
  }

  @Test
  fun `join group targets the general channel`() {
    assertEquals("type@=joingroup/rid@=12345/gid@=1/", DouyuDanmakuProtocol.joinGroup("12345"))
  }

  @Test
  fun `heartbeat uses the mrkl message type`() {
    assertEquals("type@=mrkl/", DouyuDanmakuProtocol.heartbeat())
  }

  @Test
  fun `encode produces a little endian frame with the declared length`() {
    val payload = "type@=mrkl/"
    val frame = DouyuDanmakuProtocol.encode(payload)
    val bodyLength = payload.toByteArray(Charsets.UTF_8).size

    assertEquals(DouyuDanmakuProtocol.HEADER_SIZE + bodyLength + DouyuDanmakuProtocol.TRAILER_SIZE, frame.size)

    val first = readIntLe(frame, 0)
    val second = readIntLe(frame, 4)
    assertEquals(bodyLength + 9, first)
    assertEquals(first, second)

    val type = ((frame[9].toInt() and 0xFF) shl 8) or (frame[8].toInt() and 0xFF)
    assertEquals(689, type)
    assertEquals(0, frame[10].toInt())
    assertEquals(0, frame[11].toInt())
    assertEquals(0, frame.last().toInt())
  }

  @Test
  fun `encode then decode round trips the payload`() {
    val payload = "type@=chatmsg/txt@=hello world/nn@=alice/"
    val frame = DouyuDanmakuProtocol.encode(payload)
    assertEquals(payload, DouyuDanmakuProtocol.decode(frame))
  }

  @Test
  fun `decode tolerates a trailing NUL inside the declared length`() {
    val payload = "type@=mrkl/"
    val frame = DouyuDanmakuProtocol.encode(payload)
    val padded = frame + byteArrayOf(0, 0)
    assertEquals(payload, DouyuDanmakuProtocol.decode(padded))
  }

  @Test
  fun `decode rejects frames that are too short`() {
    assertNull(DouyuDanmakuProtocol.decode(ByteArray(5)))
    assertNull(DouyuDanmakuProtocol.decode(ByteArray(DouyuDanmakuProtocol.HEADER_SIZE)))
  }

  @Test
  fun `parseFields applies the slash and at escapes`() {
    val fields = DouyuDanmakuProtocol.parseFields("type@=chatmsg/txt@=a@Sb@Ac/nn@=bob/")
    assertEquals("chatmsg", fields["type"])
    assertEquals("a/b@c", fields["txt"])
    assertEquals("bob", fields["nn"])
  }

  @Test
  fun `parseFields ignores malformed items`() {
    val fields = DouyuDanmakuProtocol.parseFields("garbage/type@=mrkl/novalue")
    assertEquals(1, fields.size)
    assertEquals("mrkl", fields["type"])
  }

  @Test
  fun `parseChat extracts the user and text for chat messages`() {
    val payload = "type@=chatmsg/nn@=小明/txt@=主播好/level@=12/"
    val chat = DouyuDanmakuProtocol.parseChat(payload)
    assertEquals("小明", chat?.first)
    assertEquals("主播好", chat?.second)
  }

  @Test
  fun `parseChat ignores non chat messages`() {
    assertNull(DouyuDanmakuProtocol.parseChat("type@=uenter/nn@=someone/"))
    assertNull(DouyuDanmakuProtocol.parseChat("type@=chatmsg/nn@=someone/txt@=   /"))
  }

  @Test
  fun `parseChat falls back to an anonymous nickname`() {
    val chat = DouyuDanmakuProtocol.parseChat("type@=chatmsg/txt@=hello/")
    assertEquals("匿名", chat?.first)
  }

  @Test
  fun `concatenate keeps frames separable`() {
    val a = DouyuDanmakuProtocol.encode("type@=mrkl/")
    val b = DouyuDanmakuProtocol.encode("type@=chatmsg/txt@=hi/nn@=x/")
    val joined = DouyuDanmakuProtocol.concatenate(a, b)
    assertEquals(a.size + b.size, joined.size)
    assertTrue(DouyuDanmakuProtocol.decode(a)!!.startsWith("type@=mrkl"))
    assertTrue(DouyuDanmakuProtocol.decode(b)!!.contains("txt@=hi"))
  }

  private fun readIntLe(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
      ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
      ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
      ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
