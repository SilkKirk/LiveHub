package com.livehub.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The key grammar of the follow record set.
 *
 * There is one kind of record — a followed room — and the key has to be parseable back into
 * (platform, roomId). The shape that actually bites is Bilibili's `"1:2"` style id: the platform
 * part is colon free by construction, but the room id is not guaranteed to be, so the parser takes
 * "everything after the first colon" rather than splitting on every colon.
 *
 * The retired prefixes (`group:` / `member:` / `fav:`) are pinned here too: a version-2 payload can
 * still arrive from an older peer, and it must decode to null rather than be mistaken for a room.
 */
class LibraryKeyTest {

  @Test
  fun `room keys round trip`() {
    val key = LibraryKey.Room(Platform.DOUYU, "288016")
    assertEquals("room:douyu:288016", key.encode())
    assertEquals(key, LibraryKey.decode("room:douyu:288016"))
  }

  @Test
  fun `a room id containing a colon survives`() {
    // Bilibili's `parent:area` is only ever a category id, but a room id is not contractually
    // colon free, and a naive split would cut it in half.
    val key = LibraryKey.Room(Platform.BILIBILI, "1:2")
    assertEquals("room:bilibili:1:2", key.encode())
    assertEquals(key, LibraryKey.decode("room:bilibili:1:2"))
  }

  @Test
  fun `an unknown prefix decodes to null instead of guessing`() {
    // The pre-version-2 vocabulary was a bare `<platform>:<roomId>`; it must not silently parse as
    // a room, otherwise a migration that never ran would look like it had.
    assertNull(LibraryKey.decode("douyu:288016"))
    assertNull(LibraryKey.decode(""))
    assertNull(LibraryKey.decode("nonsense"))
  }

  /**
   * The three kinds round 53 removed. A version-2 peer still sends them, and the store drops them;
   * decoding has to refuse them so they can never reach [LibraryKey.Room] code paths.
   */
  @Test
  fun `retired record kinds decode to null`() {
    assertNull(LibraryKey.decode("group:6f1c9a2e-7b3d-4e55-9a1c-2d8e4f6a0b71"))
    assertNull(LibraryKey.decode("member:abc-123:huya:660000"))
    assertNull(LibraryKey.decode("fav:bilibili:1:2"))
  }

  @Test
  fun `a malformed body decodes to null`() {
    assertNull(LibraryKey.decode("room:"))
    assertNull(LibraryKey.decode("room:douyu"))
    assertNull(LibraryKey.decode("room:douyu:"))
    assertNull(LibraryKey.decode("room:twitch:1"))
  }

  @Test
  fun `every platform id survives a round trip`() {
    Platform.entries.forEach { platform ->
      val room = LibraryKey.Room(platform, "1")
      assertEquals(room, LibraryKey.decode(room.encode()))
    }
  }
}
