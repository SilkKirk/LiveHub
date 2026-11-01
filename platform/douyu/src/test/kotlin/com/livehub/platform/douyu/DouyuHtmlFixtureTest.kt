package com.livehub.platform.douyu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Parser checks against a **captured** Douyu directory page.
 *
 * The fixture in `src/test/resources/fixtures/douyu-directory-lol.html` is a byte-for-byte
 * slice of `https://www.douyu.com/directory/game/LOL` taken while the endpoint was live,
 * containing the first 13 room cards. Hand-written fixtures hid two real regressions in
 * the past (a renamed `title` attribute, an extra wrapper element), so the parser is
 * pinned to actual markup.
 */
class DouyuHtmlFixtureTest {

  private val html: String by lazy {
    DouyuHtmlFixtureTest::class.java
      .getResourceAsStream("/fixtures/douyu-directory-lol.html")!!
      .bufferedReader()
      .use { it.readText() }
  }

  @Test
  fun `the fixture is present and non-trivial`() {
    assertTrue(html.length > 5_000, "fixture length was ${html.length}")
    assertTrue(html.contains("/directory/game"), "fixture should be a directory page")
  }

  @Test
  fun `every room card on the captured page is parsed`() {
    val rooms = DouyuHtmlParser.roomsFromDirectory(html)

    assertEquals(13, rooms.size, "captured page has 13 distinct room cards")
    assertTrue(rooms.all { it.platform == com.livehub.core.model.Platform.DOUYU })
    assertTrue(rooms.all { it.roomId.isNotBlank() })
    assertTrue(rooms.all { it.title.isNotBlank() }, "titles must not be blank")
  }

  @Test
  fun `room ids are numeric and unique`() {
    val rooms = DouyuHtmlParser.roomsFromDirectory(html)

    assertTrue(rooms.all { room -> room.roomId.all(Char::isDigit) })
    assertEquals(rooms.size, rooms.map { it.roomId }.toSet().size, "no duplicate rooms")
  }

  @Test
  fun `a known room from the capture is present with its real title`() {
    val rooms = DouyuHtmlParser.roomsFromDirectory(html)
    val room = rooms.firstOrNull { it.roomId == "6770423" }

    assertTrue(room != null, "room 6770423 should be on the captured page")
    assertEquals("社恐文静内向的小女生", room!!.title)
  }

  @Test
  fun `the limit is honoured without dropping earlier rooms`() {
    val limited = DouyuHtmlParser.roomsFromDirectory(html, limit = 5)

    assertEquals(5, limited.size)
    assertEquals(
      DouyuHtmlParser.roomsFromDirectory(html, limit = 13).take(5).map { it.roomId },
      limited.map { it.roomId },
    )
  }

  @Test
  fun `an empty page yields no rooms instead of throwing`() {
    assertTrue(DouyuHtmlParser.roomsFromDirectory("").isEmpty())
    assertTrue(DouyuHtmlParser.roomsFromDirectory("<html><body>no cards</body></html>").isEmpty())
  }
}
