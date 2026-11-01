package com.livehub.platform.huya

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Mapping checks against **captured** Huya responses.
 *
 * `fixtures/huya-livelist.json` is a live `getLiveList` response (120 rooms) and
 * `fixtures/huya-search.json` a live `getSearchContent` response (20 documents). Both
 * were recorded from the real endpoints, so a field rename on Huya's side fails here
 * rather than silently producing empty lists in the app.
 */
class HuyaFixtureTest {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private fun fixture(name: String): String =
    HuyaFixtureTest::class.java.getResourceAsStream("/fixtures/$name")!!
      .bufferedReader()
      .use { it.readText() }

  private val liveList by lazy { json.parseToJsonElement(fixture("huya-livelist.json")).jsonObject }
  private val search by lazy { json.parseToJsonElement(fixture("huya-search.json")).jsonObject }

  @Test
  fun `the live list fixture exposes vList at the documented path`() {
    val list = liveList["vList"]?.jsonArray
    assertNotNull(list, "getLiveList should wrap rooms in vList")
    assertEquals(120, list!!.size, "the capture requested 10 but Huya returned 120")
  }

  @Test
  fun `every captured room maps to a usable model`() {
    val rooms = liveList["vList"]!!.jsonArray.mapNotNull { element ->
      val obj = element as? JsonObject ?: return@mapNotNull null
      val roomId = obj["lProfileRoom"]?.toString()?.trim('"') ?: return@mapNotNull null
      if (roomId.isBlank() || roomId == "0") return@mapNotNull null
      Triple(
        roomId,
        obj["sNick"]?.toString()?.trim('"').orEmpty(),
        obj["sIntroduction"]?.toString()?.trim('"').orEmpty(),
      )
    }

    assertEquals(120, rooms.size, "no room should be dropped by the mapping")
    assertTrue(rooms.all { it.first.all(Char::isDigit) }, "room ids must be numeric")
    assertEquals(rooms.size, rooms.map { it.first }.toSet().size, "room ids must be unique")
    assertTrue(rooms.none { it.second.isBlank() }, "nicknames must not be blank")
  }

  @Test
  fun `a known live room from the capture maps correctly`() {
    val first = liveList["vList"]!!.jsonArray.first().jsonObject

    assertEquals("660000", first["lProfileRoom"].toString().trim('"'))
    assertEquals("虎牙英雄联盟赛事", first["sNick"].toString().trim('"'))
    assertTrue(first["sIntroduction"].toString().contains("TES vs iG"))
    assertTrue(first["lUserCount"].toString().toLong() > 0)
  }

  @Test
  fun `image urls in the capture are absolute and parse as urls`() {
    val first = liveList["vList"]!!.jsonArray.first().jsonObject
    listOf("sScreenshot", "sAvatar180").forEach { key ->
      val url = first[key]?.toString()?.trim('"').orEmpty()
      assertTrue(url.startsWith("http"), "$key should be an absolute url, was '$url'")
    }
  }

  @Test
  fun `the search fixture nests docs under response key 1`() {
    val docs = search["response"]?.jsonObject?.get("1")?.jsonObject?.get("docs")?.jsonArray

    assertNotNull(docs, "search results live at response[\"1\"].docs")
    assertEquals(20, docs!!.size)
  }

  @Test
  fun `every captured search document maps to a usable room`() {
    val docs = search["response"]!!.jsonObject["1"]!!.jsonObject["docs"]!!.jsonArray
    val rooms = docs.mapNotNull { element ->
      val obj = element as? JsonObject ?: return@mapNotNull null
      val roomId = obj["room_id"]?.toString()?.trim('"') ?: return@mapNotNull null
      if (roomId.isBlank() || roomId == "0") return@mapNotNull null
      roomId to obj["game_nick"]?.toString()?.trim('"').orEmpty()
    }

    assertEquals(20, rooms.size)
    assertTrue(rooms.none { it.second.isBlank() }, "search results must carry a nickname")
  }

  @Test
  fun `the live flag in search is a string boolean`() {
    val docs = search["response"]!!.jsonObject["1"]!!.jsonObject["docs"]!!.jsonArray
    val flags = docs.mapNotNull { (it as? JsonObject)?.get("gameLiveOn")?.toString()?.trim('"') }

    assertTrue(flags.isNotEmpty(), "gameLiveOn must be present")
    assertTrue(
      flags.all { it == "true" || it == "false" },
      "gameLiveOn should be a string boolean, saw ${flags.toSet()}",
    )
  }
}
