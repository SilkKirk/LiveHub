package com.livehub.platform.douyu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DouyuSignerTest {

  @Test
  fun `sign produces the four required fields`() {
    val signed = DouyuSigner.sign(realRoomId = "9999", did = DouyuSigner.DEFAULT_DID, tt = 1_700_000_000)

    val fields = signed.split('&').map { it.substringBefore('=') }
    assertEquals(listOf("v", "did", "tt", "sign"), fields)
    assertTrue(signed.contains("tt=1700000000"))
    assertTrue(signed.contains("did=${DouyuSigner.DEFAULT_DID}"))
  }

  @Test
  fun `sign is deterministic for identical inputs`() {
    val a = DouyuSigner.sign("9999", DouyuSigner.DEFAULT_DID, 1_700_000_000)
    val b = DouyuSigner.sign("9999", DouyuSigner.DEFAULT_DID, 1_700_000_000)
    assertEquals(a, b)
  }

  @Test
  fun `sign changes when the timestamp changes`() {
    val a = DouyuSigner.sign("9999", DouyuSigner.DEFAULT_DID, 1_700_000_000)
    val b = DouyuSigner.sign("9999", DouyuSigner.DEFAULT_DID, 1_700_000_001)
    assertNotEquals(a, b)
  }

  @Test
  fun `sign changes when the room changes`() {
    val a = DouyuSigner.sign("9999", DouyuSigner.DEFAULT_DID, 1_700_000_000)
    val b = DouyuSigner.sign("8888", DouyuSigner.DEFAULT_DID, 1_700_000_000)
    assertNotEquals(a, b)
  }

  @Test
  fun `v field is the md5 of room + did + tt`() {
    val tt = 1_700_000_000L
    val signed = DouyuSigner.sign("9999", "did123", tt)
    val v = signed.substringAfter("v=").substringBefore('&')
    assertEquals(
      com.livehub.core.network.Crypto.md5Hex("9999" + "did123" + tt),
      v,
    )
  }

  @Test
  fun `sign field uses the reversed string and timestamp`() {
    val tt = 1_700_000_000L
    val room = "9999"
    val did = "did123"
    val signed = DouyuSigner.sign(room, did, tt)
    val v = signed.substringAfter("v=").substringBefore('&')
    val expectedSign = com.livehub.core.network.Crypto.md5Hex((room + did + v).reversed() + tt)
    assertEquals(expectedSign, signed.substringAfter("sign="))
  }

  @Test
  fun `randomDid is a 32 char lowercase hex string`() {
    val did = DouyuSigner.randomDid()
    assertEquals(32, did.length)
    assertTrue(did.all { it in "0123456789abcdef" })
    assertNotEquals(did, DouyuSigner.randomDid())
  }

  @Test
  fun `sign rejects an empty room id`() {
    org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
      DouyuSigner.sign("")
    }
  }

  @Test
  fun `parseEncSource reads the room keyed entry`() {
    val json = kotlinx.serialization.json.Json
    val root = json.parseToJsonElement("""{"error":0,"data":{"room9999":"function ub98484234(){return 'x'}"}}""")
      .let { it as kotlinx.serialization.json.JsonObject }

    val source = DouyuSigner.parseEncSource(root, "9999")
    assertNotNull(source)
    assertTrue(source!!.contains("ub98484234"))

    assertNull(DouyuSigner.parseEncSource(json.parseToJsonElement("""{"data":{}}""")
      .let { it as kotlinx.serialization.json.JsonObject }, "9999"))
  }

  private fun assertNotEquals(a: String, b: String) = assertTrue(a != b, "expected $a != $b")
}
