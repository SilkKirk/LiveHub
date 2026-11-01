package com.livehub.platform.bilibili

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Mapping checks against **captured** Bilibili responses.
 *
 * `fixtures/bilibili-index-list.json` is a live hot-tab response (11 modules) and
 * `fixtures/bilibili-room-info.json` a live `Room/get_info` response. The hot tab is
 * used because it needs no `w_rid` signature, so the fixture stays reproducible.
 */
class BilibiliFixtureTest {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private fun fixture(name: String): String =
    BilibiliFixtureTest::class.java.getResourceAsStream("/fixtures/$name")!!
      .bufferedReader()
      .use { it.readText() }

  private val indexList by lazy {
    json.parseToJsonElement(fixture("bilibili-index-list.json")).jsonObject
  }
  private val roomInfo by lazy {
    json.parseToJsonElement(fixture("bilibili-room-info.json")).jsonObject
  }

  @Test
  fun `the hot list answers with code 0 and modules`() {
    assertEquals(0, indexList["code"]?.jsonPrimitive?.intOrNull)

    val modules = indexList["data"]?.jsonObject?.get("room_list")?.jsonArray
    assertNotNull(modules, "the hot tab nests rooms in data.room_list")
    assertEquals(11, modules!!.size)
  }

  @Test
  fun `rooms are nested one level deeper inside each module`() {
    val modules = indexList["data"]!!.jsonObject["room_list"]!!.jsonArray
    val total = modules.sumOf { module ->
      (module as? JsonObject)?.get("list")?.jsonArray?.size ?: 0
    }

    // The adapter flattens every module list; if this changes the grid goes empty.
    assertTrue(total >= 10, "expected at least one module to carry rooms, saw $total")
  }

  @Test
  fun `every flattened room has the fields the adapter reads`() {
    val modules = indexList["data"]!!.jsonObject["room_list"]!!.jsonArray
    val rooms = modules.flatMap { module ->
      ((module as? JsonObject)?.get("list")?.jsonArray ?: emptyList()).mapNotNull { it as? JsonObject }
    }

    assertTrue(rooms.isNotEmpty())
    rooms.forEach { room ->
      val roomId = room["roomid"]?.jsonPrimitive?.contentOrNull
      assertNotNull(roomId, "roomid is required")
      assertTrue(roomId!!.isNotBlank())
      assertNotNull(room["title"]?.jsonPrimitive?.contentOrNull, "title is required")
      assertNotNull(room["uname"]?.jsonPrimitive?.contentOrNull, "uname is required")
    }
  }

  @Test
  fun `a known live room from the capture is present`() {
    val modules = indexList["data"]!!.jsonObject["room_list"]!!.jsonArray
    val rooms = modules.flatMap { module ->
      ((module as? JsonObject)?.get("list")?.jsonArray ?: emptyList()).mapNotNull { it as? JsonObject }
    }

    val room = rooms.firstOrNull { it["roomid"]?.jsonPrimitive?.contentOrNull == "4298064" }
    assertTrue(room != null, "room 4298064 should be on the captured hot tab")
    assertEquals("Luminous_J", room!!["uname"]?.jsonPrimitive?.contentOrNull)
  }

  @Test
  fun `live status uses 1 for live rooms`() {
    val modules = indexList["data"]!!.jsonObject["room_list"]!!.jsonArray
    val statuses = modules.flatMap { module ->
      ((module as? JsonObject)?.get("list")?.jsonArray ?: emptyList())
        .mapNotNull { (it as? JsonObject)?.get("live_status")?.jsonPrimitive?.intOrNull }
    }

    assertTrue(statuses.isNotEmpty(), "live_status must be present so offline rooms can be filtered")
    assertTrue(statuses.all { it == 1 }, "the hot tab should only carry live rooms")
  }

  @Test
  fun `the room info fixture maps title live status and viewers`() {
    assertEquals(0, roomInfo["code"]?.jsonPrimitive?.intOrNull)

    val data = roomInfo["data"]?.jsonObject
    assertNotNull(data, "Room/get_info nests fields under data")
    assertEquals(1, data!!["live_status"]?.jsonPrimitive?.intOrNull, "room 6 is live")
    assertNotNull(data["title"]?.jsonPrimitive?.contentOrNull)
    assertNotNull(data["user_cover"]?.jsonPrimitive?.contentOrNull)
    assertTrue(
      (data["watched_show"]?.jsonObject?.get("num")?.jsonPrimitive?.contentOrNull ?: "0").isNotBlank(),
    )
  }
}
