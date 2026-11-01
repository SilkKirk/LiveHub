package com.livehub.platform.douyin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the danmaku handshake assembly.
 *
 * The signature itself cannot be asserted (it is produced by the site's JS and changes per
 * call), but everything around it can: the parameter set, the order, the escaping, the stub
 * the signature is computed over, and the page parsing that yields the room id. A wrong
 * parameter set is exactly what made the socket unopenable before.
 */
class DouyinDanmakuHandshakeTest {

  private val init = DouyinDanmakuHandshake.RoomInit(
    roomId = "7380000000000000000",
    userUniqueId = "123456789012",
    cookieHeader = DouyinSigner.DEFAULT_COOKIE,
  )

  @Test
  fun `query carries every parameter the web client sends`() {
    val query = DouyinDanmakuHandshake.query(init, timestamp = 1_700_000_000_000)

    val required = listOf(
      "app_name=douyin_web",
      "version_code=180800",
      "webcast_sdk_version=1.3.0",
      "update_version_code=1.3.0",
      "compress=gzip",
      "aid=6383",
      "live_id=1",
      "did_rule=3",
      "endpoint=live_pc",
      "support_wrds=1",
      "user_unique_id=123456789012",
      "device_platform=web",
      "cookie_enabled=true",
      "browser_name=Mozilla",
      "browser_online=true",
      "tz_name=Asia/Shanghai",
      "identity=audience",
      "room_id=7380000000000000000",
      "heartbeatDuration=0",
    )
    required.forEach { pair ->
      assertTrue(query.contains(pair), "missing $pair in $query")
    }
  }

  @Test
  fun `query seeds the cursor with the timestamp`() {
    val query = DouyinDanmakuHandshake.query(init, timestamp = 1_700_000_000_000)
    assertTrue(query.contains("cursor=h-1_t-1700000000000_r-1_d-1_u-1"), query)
  }

  @Test
  fun `query percent-encodes the host and im path`() {
    val query = DouyinDanmakuHandshake.query(init, timestamp = 1_700_000_000_000)
    assertTrue(query.contains("host=https%3A%2F%2Flive.douyin.com"), query)
    assertTrue(query.contains("im_path=%2Fwebcast%2Fim%2Ffetch%2F"), query)
  }

  @Test
  fun `buildUrl appends the signature last`() {
    val url = DouyinDanmakuHandshake.buildUrl(
      base = DouyinDanmakuHandshake.WS_BASES.first(),
      init = init,
      signature = "abc123",
      timestamp = 1_700_000_000_000,
    )
    assertNotNull(url)
    assertTrue(url!!.startsWith("wss://webcast5-ws-web-hl.douyin.com/webcast/im/push/v2/?"), url)
    assertTrue(url.endsWith("&signature=abc123"), url)
  }

  /** A blank token means the JS engine is unavailable; retrying the socket is pointless. */
  @Test
  fun `buildUrl refuses a blank signature`() {
    assertNull(
      DouyinDanmakuHandshake.buildUrl(
        base = DouyinDanmakuHandshake.WS_BASES.first(),
        init = init,
        signature = "",
      ),
    )
    assertNull(
      DouyinDanmakuHandshake.buildUrl(
        base = DouyinDanmakuHandshake.WS_BASES.first(),
        init = init,
        signature = null,
      ),
    )
  }

  @Test
  fun `three push hosts are offered so a dead node can be skipped`() {
    assertEquals(3, DouyinDanmakuHandshake.WS_BASES.size)
    assertTrue(DouyinDanmakuHandshake.WS_BASES.all { it.startsWith("wss://") })
    assertEquals(DouyinDanmakuHandshake.WS_BASES.size, DouyinDanmakuHandshake.WS_BASES.distinct().size)
  }

  @Test
  fun `user unique id is read from escaped and plain json`() {
    assertEquals(
      "7456123456789012345",
      DouyinDanmakuHandshake.extractUserUniqueId("""{"x":\"user_unique_id\":\"7456123456789012345\"}"""),
    )
    assertEquals(
      "7456123456789012345",
      DouyinDanmakuHandshake.extractUserUniqueId("""{"user_unique_id": "7456123456789012345"}"""),
    )
    assertEquals("", DouyinDanmakuHandshake.extractUserUniqueId("<html></html>"))
  }

  /**
   * The page's own `roomInfo.roomId`, when it carries one.
   *
   * Verified against the live page: it currently sends `\"roomId\":\"\"` for every room
   * (checked on four web_rids, including two on air), so this branch does not fire in
   * practice and the API fallback in `DouyinAdapter.resolveInit` is what actually supplies
   * the numeric id. The pattern is kept because the field is the right one and Douyin has
   * populated it before; the test pins the shape rather than claiming it is reachable.
   */
  @Test
  fun `room id is read from the roomInfo block when populated`() {
    val escaped = """{"s":"\"roomInfo\":{\"roomId\":\"7380123456789012345\",\"web_rid\":\"164485212\"}"}"""
    assertEquals("7380123456789012345", DouyinDanmakuHandshake.extractRoomId(escaped))

    // The shape the live page actually sends: an empty roomId, which must not be returned.
    val empty = """{"s":"\"roomInfo\":{\"roomId\":\"\",\"web_rid\":\"164485212\"}"}"""
    assertEquals("", DouyinDanmakuHandshake.extractRoomId(empty))
    assertEquals("", DouyinDanmakuHandshake.extractRoomId(""))
  }

  @Test
  fun `the random user unique id is twelve digits`() {
    val id = DouyinDanmakuHandshake.randomUserUniqueId()
    assertEquals(12, id.length)
    assertTrue(id.all { it.isDigit() }, id)
  }

  @Test
  fun `wsBaseIndexCycle stays inside the list`() {
    val bases = DouyinDanmakuHandshake.WS_BASES
    (0..7).forEach { index -> assertNotNull(bases[index % bases.size]) }
  }
}

/** Tests for the deterministic part of the JS handshake: the MD5 stub. */
class DouyinMsdkStubTest {

  /**
   * Pins the stub to a known digest.
   *
   * The digest is the *only* deterministic piece of the signature, so it is what fixes the
   * parameter set. Verified independently: the same input produced
   * `b5fe08c8a92fb38880f89a3a56a46b43` under Node, and that digest is what V8 then turns
   * into a valid token.
   */
  @Test
  fun `stub matches the independently computed digest`() {
    assertEquals(
      "b5fe08c8a92fb38880f89a3a56a46b43",
      DouyinMsdkSigner.msStub(
        roomId = "7380000000000000000",
        userUniqueId = "123456789012",
        webcastSdkVersion = "1.3.0",
      ),
    )
  }

  @Test
  fun `stub changes with the room, the visitor and the sdk version`() {
    val base = DouyinMsdkSigner.msStub("1", "2", "1.3.0")
    assertTrue(base != DouyinMsdkSigner.msStub("9", "2", "1.3.0"))
    assertTrue(base != DouyinMsdkSigner.msStub("1", "9", "1.3.0"))
    assertTrue(base != DouyinMsdkSigner.msStub("1", "2", "1.4.0"))
  }

  /**
   * The stub for a **real** live room, taken from a handshake that actually worked.
   *
   * This is the strongest evidence available without an emulator: the digest below was the
   * input to `getMSSDKSignature` in a Node probe that connected to
   * `webcast5-ws-web-hl.douyin.com`, received 61 frames and decoded 82 real chat messages
   * (`KPL王者荣耀职业联赛`'s room). If the stub construction ever drifts — a key renamed, a
   * fixed value changed, the order altered — this digest changes and the handshake breaks.
   */
  @Test
  fun `stub matches the digest that produced a working handshake`() {
    assertEquals(
      "2affaa9a00a0cd469bcdc03f90b5a97e",
      DouyinMsdkSigner.msStub(
        roomId = "7703137625093278464",
        userUniqueId = "7514508016932636197",
        webcastSdkVersion = "1.3.0",
      ),
    )
  }

  @Test
  fun `stub key order is the documented one`() {
    assertEquals(
      listOf(
        "live_id", "aid", "version_code", "webcast_sdk_version", "room_id",
        "sub_room_id", "sub_channel_id", "did_rule", "user_unique_id",
        "device_platform", "device_type", "ac", "identity",
      ),
      DouyinMsdkSigner.MS_STUB_KEYS,
    )
  }
}
