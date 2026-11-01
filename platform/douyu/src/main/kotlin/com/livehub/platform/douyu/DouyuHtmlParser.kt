package com.livehub.platform.douyu

import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses Douyu directory pages and room payloads into models.
 *
 * The JSON list endpoints (`newRecList`, `mixListV1`) were retired: as of late 2026
 * they answer `{"error":1,"msg":"系统异常"}` or an empty `rl` array for every category.
 * The directory page still renders the first page of rooms **server side**, so scraping
 * the anchors is now the only reliable way to list a category, and it is what this
 * parser targets.
 */
object DouyuHtmlParser {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  /**
   * Extracts room cards from a directory page.
   *
   * Each card is an `<a href="/<roomId>" title="<title>">`. Douyu renders 40 per page;
   * duplicates appear because the same anchor is repeated inside the card markup, so
   * results are de-duplicated by room id while preserving page order.
   */
  fun roomsFromDirectory(html: String, limit: Int = 40): List<Streamer> {
    if (html.isBlank()) return emptyList()
    val seen = LinkedHashSet<String>()
    val rooms = mutableListOf<Streamer>()

    ROOM_ANCHOR.findAll(html).forEach { match ->
      val roomId = match.groupValues[1]
      val title = match.groupValues[2].trim()
      if (roomId.isEmpty() || !seen.add(roomId)) return@forEach
      rooms += Streamer(
        platform = Platform.DOUYU,
        roomId = roomId,
        streamerName = "",
        title = title,
        coverUrl = null,
        avatarUrl = null,
        viewerCount = 0,
        isLive = true,
        updatedAt = System.currentTimeMillis(),
      )
    }

    // Images and nicknames live in a parallel structure keyed by room id.
    val covers = coverUrls(html)
    val nicknames = nicknames(html)
    return rooms.take(limit).map { room ->
      room.copy(
        coverUrl = covers[room.roomId] ?: room.coverUrl,
        streamerName = nicknames[room.roomId] ?: room.streamerName,
      )
    }
  }

  /** `https://rpic.douyucdn.cn/...` thumbnails, paired with the room they belong to. */
  private fun coverUrls(html: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    // Cards embed the room id in a data attribute next to the image.
    COVER_PAIR.findAll(html).forEach { match ->
      val roomId = match.groupValues[1]
      val url = match.groupValues[2].replace("&amp;", "&")
      if (roomId.isNotBlank() && url.isNotBlank()) result.putIfAbsent(roomId, url)
    }
    return result
  }

  private fun nicknames(html: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    NICK_PAIR.findAll(html).forEach { match ->
      val roomId = match.groupValues[1]
      val nick = match.groupValues[2].trim()
      if (roomId.isNotBlank() && nick.isNotBlank()) result.putIfAbsent(roomId, nick)
    }
    return result
  }

  /**
   * Reads the `window.$DATA` blob Douyu embeds on directory pages.
   *
   * The blob is not valid JSON on its own (it ends with `;` and contains a few JS
   * literals), so the parser only uses it for the category display name and total
   * count; room data comes from the anchors.
   */
  fun categoryNameFromPage(html: String): String? {
    val match = CATEGORY_TITLE.find(html) ?: return null
    return match.groupValues[1].trim().ifBlank { null }
  }

  /** Verifies a `betard` payload is still the shape the adapter expects. */
  fun looksLikeBetard(raw: String): Boolean =
    runCatching { json.parseToJsonElement(raw).jsonObject.containsKey("room") }.getOrDefault(false)

  /** Reads `room.show_status`, which drives the live flag. */
  fun showStatus(root: JsonObject): Int =
    root["room"]?.jsonObject?.get("show_status")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

  private val ROOM_ANCHOR = Regex("""href="/(\d{3,10})"\s+target="_blank"\s+title="([^"]*)"""")
  private val COVER_PAIR = Regex(
    """data-roomid="(\d+)"[\s\S]{0,400}?src="(https?://[^"]+?\.(?:jpg|jpeg|png|webp)[^"]*)"""",
    RegexOption.IGNORE_CASE,
  )
  private val NICK_PAIR = Regex(
    """href="/(\d{3,10})"[^>]*>[\s\S]{0,200}?DyLiveCardTitle-cardAuth[^>]*>([^<]{2,30})<""",
  )
  private val CATEGORY_TITLE = Regex("""<title>([^<|]*)""")
}
