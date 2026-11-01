package com.livehub.platform.douyu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The category-to-slug mapping.
 *
 * This exists because the first implementation guessed slugs and fell back to the
 * numeric `cate2Id`, which 404s: `https://www.douyu.com/directory/game/86` is not a
 * page. The category endpoint publishes the real slug as `shortName`
 * (`英雄联盟 -> LOL`, `王者荣耀 -> wzry`, `一起看 -> yqk`), verified against 12
 * categories, so the model carries it and nothing is guessed.
 */
class DouyuCategorySlugTest {

  @Test
  fun `a slug from the category endpoint always wins`() {
    assertEquals("LOL", DouyuCategorySlug.forId("1", knownSlug = "LOL"))
    assertEquals("wzry", DouyuCategorySlug.forId("181", knownSlug = "wzry"))
    // Even for an id the fallback table has never seen.
    assertEquals("newgame", DouyuCategorySlug.forId("999999", knownSlug = "newgame"))
  }

  @Test
  fun `a blank known slug falls back to the table`() {
    assertEquals("LOL", DouyuCategorySlug.forId("1", knownSlug = ""))
    assertEquals("LOL", DouyuCategorySlug.forId("1", knownSlug = "   "))
    assertEquals("LOL", DouyuCategorySlug.forId("1", knownSlug = null))
  }

  @Test
  fun `the fallback table holds real slugs, never numeric ids`() {
    // The regression this guards: `/directory/game/86` answers 404, so any numeric
    // value here is a bug that makes the whole category unloadable.
    listOf("1", "181", "1920", "1092", "3", "194", "208").forEach { id ->
      val slug = DouyuCategorySlug.forId(id)
      assertNotNull(slug, "category $id should have a fallback")
      assertTrue(
        slug!!.any { it.isLetter() },
        "slug for $id was '$slug' — numeric slugs do not resolve on Douyu",
      )
    }
  }

  @Test
  fun `known categories map to the slugs Douyu actually serves`() {
    assertEquals("LOL", DouyuCategorySlug.forId("1"))
    assertEquals("wzry", DouyuCategorySlug.forId("181"))
    assertEquals("LOLM", DouyuCategorySlug.forId("1920"))
    assertEquals("DNF", DouyuCategorySlug.forId("3"))
    assertEquals("yqk", DouyuCategorySlug.forId("208"))
  }

  @Test
  fun `an unknown category without a known slug reports that it is unsupported`() {
    assertNull(DouyuCategorySlug.forId("999999"))
    assertNull(DouyuCategorySlug.forId("999999", knownSlug = null))
  }

  @Test
  fun `reverse lookup finds the id for a slug`() {
    assertEquals("1", DouyuCategorySlug.idFor("LOL"))
    assertEquals("1", DouyuCategorySlug.idFor("lol"))
    assertNull(DouyuCategorySlug.idFor("not-a-real-slug"))
  }
}
