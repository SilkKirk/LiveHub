package com.livehub.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The key grammar of the library record set.
 *
 * Every record kind shares one record set, so the key has to be both parseable and unambiguous.
 * The two shapes that actually bite are the category ids: Bilibili encodes a partition as
 * `"1:2"` and Douyin as `"partitionType:partition"`, so the trailing part of a `fav:` key
 * contains colons and a naive `split(":")` would cut it in half.
 */
class LibraryKeyTest {

  @Test
  fun `room keys round trip`() {
    val key = LibraryKey.Room(Platform.DOUYU, "288016")
    assertEquals("room:douyu:288016", key.encode())
    assertEquals(key, LibraryKey.decode("room:douyu:288016"))
  }

  @Test
  fun `group keys round trip`() {
    val id = "6f1c9a2e-7b3d-4e55-9a1c-2d8e4f6a0b71"
    assertEquals("group:$id", LibraryKey.Group(id).encode())
    assertEquals(LibraryKey.Group(id), LibraryKey.decode("group:$id"))
  }

  @Test
  fun `favourite keys keep a bilibili category id intact`() {
    // Bilibili's `parent:area` would be split in two by a naive parse.
    val key = LibraryKey.Favorite(Platform.BILIBILI, "1:2")
    assertEquals("fav:bilibili:1:2", key.encode())
    assertEquals(key, LibraryKey.decode("fav:bilibili:1:2"))
  }

  @Test
  fun `favourite keys keep a douyin partition id intact`() {
    val key = LibraryKey.Favorite(Platform.DOUYIN, "partitionType:partition")
    assertEquals("fav:douyin:partitionType:partition", key.encode())
    assertEquals(key, LibraryKey.decode(key.encode()))
  }

  @Test
  fun `member keys carry the group the platform and the room`() {
    val key = LibraryKey.Member("abc-123", Platform.HUYA, "660000")
    assertEquals("member:abc-123:huya:660000", key.encode())
    assertEquals(key, LibraryKey.decode("member:abc-123:huya:660000"))
  }

  @Test
  fun `an unknown prefix decodes to null instead of guessing`() {
    // The old vocabulary was a bare `<platform>:<roomId>`; it must not silently parse as a room,
    // otherwise a migration that never ran would look like it had.
    assertNull(LibraryKey.decode("douyu:288016"))
    assertNull(LibraryKey.decode(""))
    assertNull(LibraryKey.decode("nonsense"))
  }

  @Test
  fun `a malformed body decodes to null`() {
    assertNull(LibraryKey.decode("room:"))
    assertNull(LibraryKey.decode("room:douyu"))
    assertNull(LibraryKey.decode("room:douyu:"))
    assertNull(LibraryKey.decode("room:twitch:1"))
    assertNull(LibraryKey.decode("group:"))
    assertNull(LibraryKey.decode("member:abc:huya"))
  }

  @Test
  fun `every platform id survives a round trip`() {
    Platform.entries.forEach { platform ->
      val room = LibraryKey.Room(platform, "1")
      assertEquals(room, LibraryKey.decode(room.encode()))
      val member = LibraryKey.Member("g", platform, "1")
      assertEquals(member, LibraryKey.decode(member.encode()))
    }
  }
}
