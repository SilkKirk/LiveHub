package com.livehub.core.model

/**
 * Typed view of a key in the follow record set.
 *
 * There is exactly **one** kind of record: a followed room. The key carries the platform and
 * the room id so a store can filter by platform without decoding the value, and so a record
 * whose payload is missing can still be identified (and refreshed) from its key alone.
 *
 * Grammar — the platform id is one of the four fixed ids, none of which contains a colon, so
 * the trailing part is "everything that is left" and may contain colons itself:
 *
 * ```
 * room:<platformId>:<roomId>
 * ```
 *
 * Version 2 of the store briefly had `group:` / `member:` / `fav:` prefixes for user-created
 * groups and pinned categories. Those record kinds are gone ([FollowStore] discards them on
 * load); the prefix-stripping parser stays because a version-2 payload can still arrive from
 * an older peer and must be ignored rather than misread.
 */
sealed interface LibraryKey {
  /** A followed room. */
  data class Room(val platform: Platform, val roomId: String) : LibraryKey

  fun encode(): String = when (this) {
    is Room -> "$ROOM_PREFIX${platform.id}:$roomId"
  }

  companion object {
    const val ROOM_PREFIX = "room:"

    /** Parses [raw] back into a typed key, or null when the prefix is unknown or retired. */
    fun decode(raw: String): LibraryKey? = when {
      raw.startsWith(ROOM_PREFIX) -> decodeRoom(raw.removePrefix(ROOM_PREFIX))
      else -> null
    }

    private fun decodeRoom(body: String): LibraryKey? {
      val split = body.indexOf(':')
      if (split <= 0 || split == body.lastIndex) return null
      val platform = Platform.fromId(body.substring(0, split)) ?: return null
      return Room(platform, body.substring(split + 1))
    }
  }
}
