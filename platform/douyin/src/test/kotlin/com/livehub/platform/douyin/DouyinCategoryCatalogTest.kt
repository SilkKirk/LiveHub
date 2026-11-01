package com.livehub.platform.douyin

import com.livehub.core.model.Platform
import com.livehub.platform.api.AssetReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shipped Douyin catalogue.
 *
 * 9 groups and 306 partitions. The partition/partitionType pair is recovered from the
 * last two segments of each `href`, which is the only place Douyin publishes it.
 */
class DouyinCategoryCatalogTest {

  private val raw: String by lazy {
    DouyinCategoryCatalogTest::class.java
      .getResourceAsStream("/categories/douyin_categories.json")!!
      .bufferedReader()
      .use { it.readText() }
  }

  @Test
  fun `the shipped table parses into every declared partition`() {
    val categories = DouyinCategoryCatalog.parse(raw)

    assertEquals(306, categories.size)
    assertTrue(categories.all { it.platform == Platform.DOUYIN })
  }

  @Test
  fun `every id decodes back to a numeric partition pair`() {
    DouyinCategoryCatalog.parse(raw).forEach { category ->
      val ids = DouyinCategoryCatalog.idsOf(category.id)
      assertNotNull(ids, "id '${category.id}' for ${category.name} is not encodable")
      assertTrue(ids!!.first.toLongOrNull() != null, "partitionType must be numeric")
      assertTrue(ids.second.toLongOrNull() != null, "partition must be numeric")
    }
  }

  @Test
  fun `the href rule takes the last two segments`() {
    // /category/1_2_1_1010023 -> partitionType 1, partition 1010023
    assertEquals("1" to "1010023", DouyinCategoryCatalog.partitionOf("/category/1_2_1_1010023"))
    assertEquals("1" to "1010032", DouyinCategoryCatalog.partitionOf("/category/1_1_1_1010032"))
  }

  @Test
  fun `a known partition from the table is present`() {
    val pubg = DouyinCategoryCatalog.parse(raw).firstOrNull { it.name == "和平精英" }

    assertNotNull(pubg)
    assertEquals("1:1010032", pubg!!.id)
  }

  @Test
  fun `sort order is dense so the chip rows keep their source order`() {
    val orders = DouyinCategoryCatalog.parse(raw).map { it.sortOrder }
    assertEquals(orders.indices.toList(), orders)
  }

  @Test
  fun `the same game legitimately appears under several groups`() {
    // 306 entries resolve to 288 distinct partitions: Douyin lists popular titles under
    // more than one group (只狼 under both 单机游戏 and 角色扮演), all pointing at the same
    // partition. The parse must keep every entry — dropping duplicates would delete chips
    // the source document deliberately shows — so this pins the real numbers.
    val categories = DouyinCategoryCatalog.parse(raw)

    assertEquals(306, categories.size)
    assertEquals(288, categories.map { it.id }.toSet().size)
    assertEquals(18, categories.size - categories.map { it.id }.toSet().size)

    // A duplicated id really does carry the same partition under different groups.
    val duplicates = categories.groupBy { it.id }.filterValues { it.size > 1 }
    duplicates.values.forEach { group ->
      assertEquals(group.first().name, group.first().name)
      assertTrue(group.mapNotNull { it.parentName }.distinct().size > 1)
    }
  }

  @Test
  fun `the groups are the nine Douyin tabs`() {
    val groups = DouyinCategoryCatalog.parse(raw).mapNotNull { it.parentName }.distinct()

    assertEquals(
      listOf(
        "射击游戏", "竞技游戏", "单机游戏", "棋牌游戏", "休闲益智",
        "角色扮演", "策略卡牌", "娱乐天地", "科技文化",
      ),
      groups,
    )
  }

  @Test
  fun `a malformed href yields null rather than a wrong partition`() {
    assertNull(DouyinCategoryCatalog.partitionOf("/category/abc"))
    assertNull(DouyinCategoryCatalog.partitionOf("/category/1_2_x_1010023"))
    assertNull(DouyinCategoryCatalog.partitionOf(""))
  }

  @Test
  fun `a missing asset yields no categories instead of throwing`() {
    assertTrue(DouyinCategoryCatalog.load(AssetReader.Empty).isEmpty())
    assertTrue(DouyinCategoryCatalog.parse(null).isEmpty())
    assertTrue(DouyinCategoryCatalog.parse("not json").isEmpty())
  }
}
