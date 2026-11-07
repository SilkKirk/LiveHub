package com.livehub.app.di

import com.livehub.core.model.LibraryKey
import com.livehub.core.model.Streamer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The JSON shape of a follow record, and nothing else.
 *
 * Split out of [FollowStore] because it is the part with no decisions in it: a field goes in, the
 * same field comes out, missing fields get a documented default. Keeping it apart means a change
 * to the *wire format* is visibly a different kind of edit from a change to the *rules*, and it is
 * the only file a reader has to check when asking "what does a `room:` record actually contain".
 *
 * Every reader here is total: a record whose value is missing, empty or malformed decodes to null
 * or to defaults rather than throwing. That matters because these values arrive from a peer, and
 * one bad record must not take the whole list down.
 */
internal object FollowCodec {

  fun fromStreamer(
    streamer: Streamer,
    now: Long,
    order: JsonElement?,
    special: Boolean = false,
  ): JsonObject = buildJsonObject {
    put("platform", streamer.platform.id)
    put("roomId", streamer.roomId)
    put("streamerName", streamer.streamerName)
    put("title", streamer.title)
    put("coverUrl", streamer.coverUrl.orEmpty())
    put("avatarUrl", streamer.avatarUrl.orEmpty())
    put("viewerCount", streamer.viewerCount)
    put("isLive", streamer.isLive)
    put("categoryName", streamer.categoryName.orEmpty())
    put("updatedAt", if (streamer.updatedAt == 0L) now else streamer.updatedAt)
    order?.let { put("order", it) }
    // Only written when set, so a version-3 record stays byte-identical for users who never
    // star anybody: an always-present `false` would rewrite every record on the next reorder.
    if (special) put("special", true)
  }

  fun toStreamer(key: LibraryKey.Room, value: JsonObject): Streamer? {
    // The room id lives in the key as well; the value is authoritative when both are present, so a
    // record whose payload disagrees with its key still renders the room the user followed.
    val roomId = value["roomId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
      ?: key.roomId.takeIf { it.isNotBlank() }
      ?: return null
    return Streamer(
      platform = key.platform,
      roomId = roomId,
      streamerName = value["streamerName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      title = value["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      coverUrl = value["coverUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
      avatarUrl = value["avatarUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
      viewerCount = value["viewerCount"]?.jsonPrimitive?.longOrNull ?: 0,
      isLive = value["isLive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
      categoryName = value["categoryName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
      updatedAt = value["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0,
    )
  }

  /** A record's position in the follow list, or [Int.MAX_VALUE] when it has none yet. */
  fun orderOf(record: JsonObject?): Int =
    record?.get("order")?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE

  /**
   * True when this room is pinned to the 特别关注 row.
   *
   * Absent means false: the field only exists on records the user actually starred, so an old
   * record (or any record written before this feature) reads as "not special" rather than needing
   * a migration pass.
   */
  fun isSpecial(record: JsonObject?): Boolean =
    record?.get("special")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
}
