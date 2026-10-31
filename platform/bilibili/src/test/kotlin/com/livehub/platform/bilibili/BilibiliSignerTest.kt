package com.livehub.platform.bilibili

import com.livehub.core.network.Crypto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BilibiliSignerTest {

  @Test
  fun `signListQuery keeps the declared parameter order`() {
    val ordered = listOf(
      "area_id" to "86",
      "page" to "1",
      "parent_area_id" to "2",
      "platform" to "web",
    )
    val signed = BilibiliSigner.signListQuery(ordered, wts = 1_700_000_000)

    val keys = signed.canonical.split('&').map { it.substringBefore('=') }
    assertEquals(listOf("area_id", "page", "parent_area_id", "platform", "wts"), keys)
    assertTrue(signed.query.endsWith("&w_rid=${signed.wRid}"))
  }

  @Test
  fun `signListQuery appends the public salt before hashing`() {
    val ordered = listOf("area_id" to "86")
    val signed = BilibiliSigner.signListQuery(ordered, wts = 1_700_000_000)
    assertEquals(
      Crypto.md5Hex(signed.canonical + BilibiliSigner.LIST_SALT),
      signed.wRid,
    )
  }

  @Test
  fun `signListQuery is stable for identical inputs`() {
    val ordered = listOf("a" to "1")
    assertEquals(
      BilibiliSigner.signListQuery(ordered, 100).wRid,
      BilibiliSigner.signListQuery(ordered, 100).wRid,
    )
  }

  @Test
  fun `signListQuery changes with the timestamp`() {
    val ordered = listOf("a" to "1")
    assertNotEquals(
      BilibiliSigner.signListQuery(ordered, 100).wRid,
      BilibiliSigner.signListQuery(ordered, 101).wRid,
    )
  }

  @Test
  fun `mixinKey picks 32 characters using the published table`() {
    val img = "7cd084941338484aae1ad9425b84077c"
    val sub = "4932caff0ff746eab6f01bf08b70ac45"
    val key = BilibiliSigner.mixinKey(img, sub)

    assertEquals(32, key.length)
    val combined = img + sub
    val table = BilibiliSigner.mixinKeyTable()
    assertEquals(table.size, 64)
    assertEquals(combined[table[0]], key[0])
    assertEquals(combined[table[31]], key[31])
  }

  @Test
  fun `mixinKey pads with NUL when the source is too short`() {
    val key = BilibiliSigner.mixinKey("ab", "cd")
    val combined = "abcd"
    val table = BilibiliSigner.mixinKeyTable()

    assertEquals(32, key.length)
    // Every slot either points at a real character of the combined key or is NUL.
    table.forEachIndexed { index, source ->
      val expected = if (source < combined.length) combined[source] else '\u0000'
      assertEquals(expected, key[index], "slot $index")
    }
  }

  @Test
  fun `keyFromUrl extracts the file stem`() {
    assertEquals(
      "7cd084941338484aae1ad9425b84077c",
      BilibiliSigner.keyFromUrl("https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png"),
    )
    assertEquals("abc", BilibiliSigner.keyFromUrl("https://x/abc.webp"))
    assertEquals("", BilibiliSigner.keyFromUrl(""))
  }

  @Test
  fun `signWbi sorts the parameters before hashing`() {
    val signed = BilibiliSigner.signWbi(
      params = mapOf("id" to "123", "type" to "0"),
      mixinKey = "mixin",
      wts = 1_700_000_000,
    )
    val keys = signed.canonical.split('&').map { it.substringBefore('=') }
    assertEquals(listOf("id", "type", "wts"), keys)
    assertEquals(Crypto.md5Hex(signed.canonical + "mixin"), signed.wRid)
  }

  @Test
  fun `wbiEncode keeps unreserved characters`() {
    assertEquals("abcXYZ019-_.~", Crypto.wbiEncode("abcXYZ019-_.~"))
  }

  @Test
  fun `wbiEncode drops the exclamation quote paren and star characters`() {
    assertEquals("abcdef", Crypto.wbiEncode("a!b'c(d)e*f"))
  }

  @Test
  fun `wbiEncode percent encodes with uppercase hex`() {
    // Uppercase hex is required: the mixin hash is compared byte for byte by the server.
    assertEquals("%E4%B8%AD", Crypto.wbiEncode("中"))
    assertEquals("%20", Crypto.wbiEncode(" "))
    assertEquals("%2F", Crypto.wbiEncode("/"))
    assertEquals("%3D", Crypto.wbiEncode("="))
  }
}

class BilibiliDanmakuProtocolTest {

  @Test
  fun `encode writes the 16 byte big endian header`() {
    val body = "hello".toByteArray()
    val packet = BilibiliDanmakuProtocol.encode(BilibiliDanmakuProtocol.OP_NOTIFICATION, body)

    assertEquals(16 + body.size, packet.size)
    assertEquals(16 + body.size, readIntBe(packet, 0))
    assertEquals(16, readShortBe(packet, 4))
    assertEquals(BilibiliDanmakuProtocol.VER_PLAIN, readShortBe(packet, 6))
    assertEquals(BilibiliDanmakuProtocol.OP_NOTIFICATION, readIntBe(packet, 8))
    assertEquals(1, readIntBe(packet, 12))
    assertEquals("hello", String(packet, 16, body.size))
  }

  @Test
  fun `auth frame carries the token and protover 1`() {
    val frame = BilibiliDanmakuProtocol.authFrame(uid = 42, roomId = "9999", token = "tok")
    val packets = BilibiliDanmakuProtocol.decode(frame)

    assertEquals(1, packets.size)
    val text = packets.first().text()
    assertTrue(text.contains("\"uid\":42"))
    assertTrue(text.contains("\"roomid\":9999"))
    assertTrue(text.contains("\"key\":\"tok\""))
    assertTrue(text.contains("\"protover\":1"))
    assertEquals(BilibiliDanmakuProtocol.OP_AUTH, packets.first().operation)
  }

  @Test
  fun `negative uid is clamped to zero`() {
    val packets = BilibiliDanmakuProtocol.decode(
      BilibiliDanmakuProtocol.authFrame(uid = -5, roomId = "1", token = "t"),
    )
    assertTrue(packets.first().text().contains("\"uid\":0"))
  }

  @Test
  fun `heartbeat has an empty body`() {
    val packets = BilibiliDanmakuProtocol.decode(BilibiliDanmakuProtocol.heartbeatFrame())
    assertEquals(1, packets.size)
    assertEquals(BilibiliDanmakuProtocol.OP_HEARTBEAT, packets.first().operation)
    assertTrue(packets.first().body.isEmpty())
  }

  @Test
  fun `decode splits concatenated packets`() {
    val a = BilibiliDanmakuProtocol.encode(2, ByteArray(0))
    val b = BilibiliDanmakuProtocol.encode(5, "{}".toByteArray())
    val packets = BilibiliDanmakuProtocol.decode(a + b)

    assertEquals(2, packets.size)
    assertEquals(2, packets[0].operation)
    assertEquals(5, packets[1].operation)
  }

  @Test
  fun `decode rejects truncated buffers without throwing`() {
    assertTrue(BilibiliDanmakuProtocol.decode(ByteArray(8)).isEmpty())
    assertTrue(BilibiliDanmakuProtocol.decode(ByteArray(0)).isEmpty())
  }

  @Test
  fun `parseDanmaku extracts nickname and text`() {
    val json = """{"cmd":"DANMU_MSG","info":[[],"你好",[0,"小明"]]}"""
    val packet = BilibiliDanmakuProtocol.Packet(
      operation = BilibiliDanmakuProtocol.OP_NOTIFICATION,
      version = BilibiliDanmakuProtocol.VER_PLAIN,
      body = json.toByteArray(),
    )

    val chat = BilibiliDanmakuProtocol.parseDanmaku(packet)
    assertEquals("小明", chat?.first)
    assertEquals("你好", chat?.second)
  }

  @Test
  fun `parseDanmaku ignores non danmaku commands`() {
    val json = """{"cmd":"SEND_GIFT","info":[]}"""
    val packet = BilibiliDanmakuProtocol.Packet(
      BilibiliDanmakuProtocol.OP_NOTIFICATION,
      BilibiliDanmakuProtocol.VER_PLAIN,
      json.toByteArray(),
    )
    assertEquals(null, BilibiliDanmakuProtocol.parseDanmaku(packet))
  }

  @Test
  fun `parseDanmaku falls back to anonymous and drops blank text`() {
    val blankNick = """{"cmd":"DANMU_MSG","info":[[],"hi",[0,""]]}"""
    assertEquals(
      "匿名",
      BilibiliDanmakuProtocol.parseDanmaku(
        BilibiliDanmakuProtocol.Packet(5, 1, blankNick.toByteArray()),
      )?.first,
    )

    val blankText = """{"cmd":"DANMU_MSG","info":[[],"   ",[0,"x"]]}"""
    assertEquals(
      null,
      BilibiliDanmakuProtocol.parseDanmaku(
        BilibiliDanmakuProtocol.Packet(5, 1, blankText.toByteArray()),
      ),
    )
  }

  @Test
  fun `parseDanmaku ignores non notification packets`() {
    val packet = BilibiliDanmakuProtocol.Packet(2, 1, "{}".toByteArray())
    assertEquals(null, BilibiliDanmakuProtocol.parseDanmaku(packet))
  }

  @Test
  fun `orderEndpoints dedupes and prefers port 443`() {
    val endpoints = listOf(
      BilibiliDanmakuProtocol.Endpoint("b.host", 2243),
      BilibiliDanmakuProtocol.Endpoint("a.host", 443),
      BilibiliDanmakuProtocol.Endpoint("a.host", 443),
    )
    val ordered = BilibiliDanmakuProtocol.orderEndpoints(endpoints)

    assertEquals(2, ordered.size)
    assertEquals("a.host", ordered.first().host)
    assertEquals("wss://a.host/sub", ordered.first().url)
    assertEquals("wss://b.host:2243/sub", ordered[1].url)
  }

  @Test
  fun `orderEndpoints falls back to the public host`() {
    val ordered = BilibiliDanmakuProtocol.orderEndpoints(emptyList())
    assertEquals(1, ordered.size)
    assertEquals(BilibiliDanmakuProtocol.WS_HOST_FALLBACK, ordered.first().host)
  }

  @Test
  fun `zlib packets are inflated and parsed recursively`() {
    val inner = BilibiliDanmakuProtocol.encode(
      BilibiliDanmakuProtocol.OP_NOTIFICATION,
      """{"cmd":"DANMU_MSG","info":[[],"zip",[0,"u"]]}""".toByteArray(),
    )
    val compressed = deflate(inner)
    val outer = buildZlibPacket(compressed)

    val packets = BilibiliDanmakuProtocol.decode(outer)
    assertEquals(1, packets.size)
    assertEquals("u", BilibiliDanmakuProtocol.parseDanmaku(packets.first())?.first)
  }

  @Test
  fun `length fields larger than the buffer stop parsing`() {
    val bogus = ByteArray(32)
    writeIntBe(bogus, 0, 9999)
    writeShortBe(bogus, 4, 16)
    writeShortBe(bogus, 6, 1)
    assertTrue(BilibiliDanmakuProtocol.decode(bogus).isEmpty())
  }

  private fun buildZlibPacket(compressed: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val header = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.BIG_ENDIAN)
    header.putInt(16 + compressed.size)
    header.putShort(16)
    header.putShort(BilibiliDanmakuProtocol.VER_ZLIB.toShort())
    header.putInt(BilibiliDanmakuProtocol.OP_NOTIFICATION)
    header.putInt(1)
    out.write(header.array())
    out.write(compressed)
    return out.toByteArray()
  }

  private fun deflate(data: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    java.util.zip.DeflaterOutputStream(out).use { it.write(data) }
    return out.toByteArray()
  }

  private fun readIntBe(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
      ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
      ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
      (bytes[offset + 3].toInt() and 0xFF)

  private fun readShortBe(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

  private fun writeIntBe(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 24).toByte()
    bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte()
    bytes[offset + 3] = value.toByte()
  }

  private fun writeShortBe(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 8).toByte()
    bytes[offset + 1] = value.toByte()
  }
}

class BilibiliCookieStoreTest {

  @Test
  fun `saving merges cookies instead of replacing them`() {
    val store = InMemoryCookieStore()
    store.save("a=1; b=2")
    store.save("b=3; c=4")

    val header = store.header()
    assertTrue(header.contains("a=1"))
    assertTrue(header.contains("b=3"))
    assertTrue(header.contains("c=4"))
  }

  @Test
  fun `clear removes every cookie`() {
    val store = InMemoryCookieStore()
    store.save("a=1")
    store.clear()
    assertEquals("", store.header())
  }

  @Test
  fun `isLoggedIn detects the session cookie`() {
    val store = InMemoryCookieStore()
    assertTrue(!store.isLoggedIn())
    store.save("SESSDATA=abc")
    assertTrue(store.isLoggedIn())
  }

  @Test
  fun `blank input is ignored`() {
    val store = InMemoryCookieStore()
    store.save("a=1")
    store.save("")
    assertEquals("a=1", store.header())
  }
}
