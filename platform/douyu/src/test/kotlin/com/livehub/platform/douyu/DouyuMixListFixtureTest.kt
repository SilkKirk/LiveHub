package com.livehub.platform.douyu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Parser checks against **captured** Douyu responses.
 *
 * `fixtures/douyu-mixlistv1.json` is a live `mixListV1/2_1/1?limit=20` response (40
 * rooms) and `fixtures/douyu-threecate.json` a live `getThreeCate?tag_id=1` response
 * (9 sub-categories). Recording them means a field rename on Douyu's side fails here
 * instead of producing an empty grid in the app.
 *
 * This replaced an HTML-scraping path: `mixListV1` was always the right endpoint, it was
 * just being called with wrong category ids, and it paginates correctly (verified: pages
 * 1, 2 and 3 of `2_1` share zero room ids).
 */
class DouyuMixListFixtureTest {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private fun fixture(name: String): String =
    DouyuMixListFixtureTest::class.java.getResourceAsStream("/fixtures/$name")!!
      .bufferedReader()
      .use { it.readText() }

  private val mixList by lazy { json.parseToJsonElement(fixture("douyu-mixlistv1.json")).jsonObject }
  private val threeCate by lazy { json.parseToJsonElement(fixture("douyu-threecate.json")).jsonObject }

  private val rooms by lazy { mixList["data"]!!.jsonObject["rl"]!!.jsonArray }

  @Test
  fun `the response is a success envelope carrying rl`() {
    assertEquals(0, mixList["code"]?.jsonPrimitive?.content?.toInt())
    assertNotNull(mixList["data"]?.jsonObject?.get("rl"), "rooms live at data.rl")
    assertEquals(40, rooms.size)
  }

  @Test
  fun `every captured room maps to a usable model`() {
    val parsed = rooms.mapNotNull { DouyuParser.mixListRoom(it) }

    assertEquals(40, parsed.size, "no room should be dropped")
    assertTrue(parsed.all { it.platform == com.livehub.core.model.Platform.DOUYU })
    assertTrue(parsed.all { it.roomId.all(Char::isDigit) }, "rid must be numeric")
    assertTrue(parsed.all { it.streamerName.isNotBlank() })
    assertTrue(parsed.all { it.title.isNotBlank() })
    assertEquals(parsed.size, parsed.map { it.roomId }.toSet().size, "room ids must be unique")
  }

  @Test
  fun `the abbreviated fields are mapped to the right places`() {
    // Douyu abbreviates everything in this payload, so a swapped key is easy to miss.
    val first = rooms.first().jsonObject
    val parsed = DouyuParser.mixListRoom(first)!!

    assertEquals(first["rid"]!!.jsonPrimitive.content, parsed.roomId)
    assertEquals(first["rn"]!!.jsonPrimitive.content, parsed.title)
    assertEquals(first["nn"]!!.jsonPrimitive.content, parsed.streamerName)
    assertEquals(first["ol"]!!.jsonPrimitive.content.toLong(), parsed.viewerCount)
    assertTrue(parsed.coverUrl!!.startsWith("http"))
    assertTrue(parsed.avatarUrl!!.startsWith("http"))
  }

  @Test
  fun `a known room from the capture maps correctly`() {
    val room = rooms.mapNotNull { DouyuParser.mixListRoom(it) }
      .firstOrNull { it.roomId == "226037" }

    assertNotNull(room, "room 226037 should be on the captured page")
    assertEquals("MQ丶蜘蛛馍", room!!.streamerName)
    assertEquals("蜘蛛馍：野区三分钟一血版本上分黑科技", room.title)
    assertTrue(room.viewerCount > 0)
  }

  @Test
  fun `mixListV1 only lists live rooms so every entry is live`() {
    val parsed = rooms.mapNotNull { DouyuParser.mixListRoom(it) }
    assertTrue(parsed.all { it.isLive })
  }

  @Test
  fun `a malformed room is skipped instead of throwing`() {
    assertNull(DouyuParser.mixListRoom(Json.parseToJsonElement("{}")))
    assertNull(DouyuParser.mixListRoom(Json.parseToJsonElement("""{"rid":0}""")))
    assertNull(DouyuParser.mixListRoom(Json.parseToJsonElement("""{"rn":"no id"}""")))
  }

  @Test
  fun `the three-cate fixture uses flat id and name keys`() {
    val items = threeCate["data"]!!.jsonArray

    assertEquals(9, items.size)
    items.forEach { item ->
      val obj = item as JsonObject
      // The reference project guessed tagId/tagName/cateId; the real shape is id/name.
      assertNotNull(obj["id"], "each sub-category carries a flat id")
      assertNotNull(obj["name"], "each sub-category carries a flat name")
      assertTrue(obj["id"]!!.jsonPrimitive.content.isNotBlank())
      assertTrue(obj["name"]!!.jsonPrimitive.content.isNotBlank())
    }
  }

  @Test
  fun `a known sub-category from the capture is present`() {
    val names = threeCate["data"]!!.jsonArray
      .map { (it as JsonObject)["name"]!!.jsonPrimitive.content }

    assertTrue("S15" in names, "expected S15 among $names")
    assertTrue("赛事直播" in names)
  }
}
