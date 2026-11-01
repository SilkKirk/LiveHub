package com.livehub.platform.bilibili

import com.livehub.core.model.Platform
import com.livehub.platform.api.AssetReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shipped Bilibili catalogue.
 *
 * 13 groups and 386 sub-categories. Each id is encoded `"<parentAreaId>:<areaId>"`,
 * which is exactly what `BilibiliArea.decode` and `second/getList` expect, so a bad
 * encode here silently returns the wrong area's rooms rather than failing.
 */
class BilibiliCategoriesTest {

  private val raw: String by lazy {
    BilibiliCategoriesTest::class.java.getResourceAsStream("/categories/bilibili_categories.json")!!
      .bufferedReader()
      .use { it.readText() }
  }

  @Test
  fun `the shipped table parses into every declared category`() {
    val categories = BilibiliCategories.parse(raw)

    assertEquals(386, categories.size)
    assertTrue(categories.all { it.platform == Platform.BILIBILI })
  }

  @Test
  fun `every id decodes back to the parent and area it came from`() {
    BilibiliCategories.parse(raw).forEach { category ->
      val ids = BilibiliCategories.idsOf(category.id)
      assertNotNull(ids, "id '${category.id}' for ${category.name} is not encodable")
      assertEquals(category.parentId, ids!!.first.toString())
    }
  }

  @Test
  fun `the encoded id is what BilibiliArea parses`() {
    // The adapter hands the raw category id straight to BilibiliArea.decode.
    BilibiliCategories.parse(raw).take(50).forEach { category ->
      val area = BilibiliApiArea.decodeForTest(category.id)
      assertEquals(category.parentId, area.parentId)
      assertEquals(BilibiliCategories.idsOf(category.id)!!.second.toString(), area.areaId)
    }
  }

  @Test
  fun `a known category from the table is present`() {
    val league = BilibiliCategories.parse(raw).firstOrNull { it.name == "英雄联盟" }

    assertNotNull(league)
    // The source document records 英雄联盟 as parent 2 / area 86.
    assertEquals("2:86", league!!.id)
  }

  @Test
  fun `ids are unique and sort order is dense`() {
    val categories = BilibiliCategories.parse(raw)

    assertEquals(categories.size, categories.map { it.id }.toSet().size)
    assertEquals(categories.indices.toList(), categories.map { it.sortOrder })
  }

  @Test
  fun `the groups are the thirteen Bilibili tabs`() {
    val groups = BilibiliCategories.parse(raw).mapNotNull { it.parentName }.distinct()

    assertEquals(
      listOf(
        "网游", "手游", "单机游戏", "虚拟主播", "娱乐", "电台", "赛事",
        "聊天室", "生活", "知识", "帮我玩", "互动玩法", "购物",
      ),
      groups,
    )
  }
  @Test
  fun `the hot tab is distinguishable from a real area`() {
    assertEquals("0:0", BilibiliCategories.HOT_ID)
    assertEquals(0 to 0, BilibiliCategories.idsOf(BilibiliCategories.HOT_ID))
  }

  @Test
  fun `a malformed id yields null rather than a wrong area`() {
    assertNull(BilibiliCategories.idsOf("not-an-id"))
    assertNull(BilibiliCategories.idsOf("2:abc"))
    assertNull(BilibiliCategories.idsOf("2"))
  }

  @Test
  fun `a missing asset yields no categories instead of throwing`() {
    assertTrue(BilibiliCategories.load(AssetReader.Empty).isEmpty())
    assertTrue(BilibiliCategories.parse(null).isEmpty())
    assertTrue(BilibiliCategories.parse("not json").isEmpty())
  }
}

/** Exposes [BilibiliArea.decode] to the test without widening its visibility. */
private object BilibiliApiArea {
  fun decodeForTest(raw: String): BilibiliArea = BilibiliArea.decode(raw)
}
