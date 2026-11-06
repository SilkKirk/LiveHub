package com.livehub.core.model

import kotlinx.serialization.Serializable

/**
 * A user-created bucket of followed rooms.
 *
 * A room can sit in any number of groups, which is why membership is **not** a field on the
 * room record: see [LibraryKey.Member]. The name is user supplied and only has to be unique
 * among live groups; [id] is a UUID and is never reused, so deleting a group and creating one
 * with the same name cannot silently adopt the old members.
 */
@Serializable
data class FollowGroup(
  val id: String,
  val name: String,
  /** Position in the group row, lowest first. */
  val order: Int = Int.MAX_VALUE,
) {
  /** Group id used as a record key, so callers never hand-build the prefix. */
  val key: String get() = LibraryKey.Group(id).encode()

  companion object {
    /**
     * The virtual group every unfiled room belongs to.
     *
     * Not a record: a room is "unfiled" exactly when it has no live member records. Giving it
     * an id that cannot be a UUID keeps it out of the id space [FollowGroup.id] uses, so no
     * real group can ever collide with it.
     */
    const val UNFILED_ID = "unfiled"

    /** Display name of the virtual group. */
    const val UNFILED_NAME = "未分类"
  }
}

/**
 * Typed view of a key in the library record set.
 *
 * The follow list, the user's groups, their favourites and the room↔group memberships all
 * live in **one** synchronised record set, so every key has to say which kind of record it is.
 * The prefix does that, and parsing it back is total: [decode] returns null for anything it
 * does not recognise instead of guessing, which is what a mis-typed key used to get.
 *
 * Grammar — every part before the last one is colon free:
 *
 * ```
 * room:<platformId>:<roomId>
 * group:<uuid>
 * fav:<platformId>:<categoryId>
 * member:<groupUuid>:<platformId>:<roomId>
 * ```
 *
 * `platformId` is one of the four fixed ids (`douyu`, `huya`, `douyin`, `bilibili`) and a group
 * id is a UUID, so neither can contain a colon. The trailing part is therefore "everything
 * that is left" and may contain colons itself — which it does: Bilibili encodes a partition as
 * `"parent:area"` and Douyin as `"partitionType:partition"`.
 */
sealed interface LibraryKey {
  /** A followed room. */
  data class Room(val platform: Platform, val roomId: String) : LibraryKey

  /** A user-created group. */
  data class Group(val id: String) : LibraryKey

  /** A category the user pinned to the home screen. */
  data class Favorite(val platform: Platform, val categoryId: String) : LibraryKey

  /** "This room is in this group". One record per pair, so two devices can add disjoint groups. */
  data class Member(val groupId: String, val platform: Platform, val roomId: String) : LibraryKey

  fun encode(): String = when (this) {
    is Room -> "$ROOM_PREFIX${platform.id}:$roomId"
    is Group -> "$GROUP_PREFIX$id"
    is Favorite -> "$FAVORITE_PREFIX${platform.id}:$categoryId"
    is Member -> "$MEMBER_PREFIX$groupId:${platform.id}:$roomId"
  }

  companion object {
    const val ROOM_PREFIX = "room:"
    const val GROUP_PREFIX = "group:"
    const val FAVORITE_PREFIX = "fav:"
    const val MEMBER_PREFIX = "member:"

    /** Parses [raw] back into a typed key, or null when the prefix is unknown. */
    fun decode(raw: String): LibraryKey? = when {
      raw.startsWith(ROOM_PREFIX) -> decodeRoomish(raw.removePrefix(ROOM_PREFIX)) { platform, rest ->
        Room(platform, rest)
      }
      raw.startsWith(MEMBER_PREFIX) -> decodeMember(raw.removePrefix(MEMBER_PREFIX))
      raw.startsWith(FAVORITE_PREFIX) -> decodeRoomish(raw.removePrefix(FAVORITE_PREFIX)) { platform, rest ->
        Favorite(platform, rest)
      }
      raw.startsWith(GROUP_PREFIX) -> raw.removePrefix(GROUP_PREFIX)
        .takeIf { it.isNotBlank() }
        ?.let { Group(it) }
      else -> null
    }

    /** `room:` and `fav:` share the shape `<platformId>:<rest>`. */
    private inline fun decodeRoomish(
      body: String,
      build: (Platform, String) -> LibraryKey,
    ): LibraryKey? {
      val split = body.indexOf(':')
      if (split <= 0 || split == body.lastIndex) return null
      val platform = Platform.fromId(body.substring(0, split)) ?: return null
      return build(platform, body.substring(split + 1))
    }

    private fun decodeMember(body: String): LibraryKey? {
      val first = body.indexOf(':')
      if (first <= 0) return null
      val groupId = body.substring(0, first)
      val rest = body.substring(first + 1)
      val second = rest.indexOf(':')
      if (second <= 0 || second == rest.lastIndex) return null
      val platform = Platform.fromId(rest.substring(0, second)) ?: return null
      return Member(groupId, platform, rest.substring(second + 1))
    }
  }
}
