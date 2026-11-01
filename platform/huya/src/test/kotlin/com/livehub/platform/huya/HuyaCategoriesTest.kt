package com.livehub.platform.huya

import com.livehub.core.model.Platform
import com.livehub.platform.api.AssetReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shipped Huya catalogue.
 *
 * Huya has no usable public catalogue endpoint, so the app ships the table it renders.
 * These tests pin the parse and the invariants the home screen relies on: every entry
 * carries a numeric `iGid` and a parent group, and nothing is dropped.
 */
class HuyaCategoriesTest {

  private val raw: String by lazy {
    HuyaCategoriesTest::class.java.getResourceAsStream("/categories/huya_categories.json")!!
      .bufferedReader()
      .use { it.readText() }
  }

  private val assets = AssetReader { name ->
    if (name == HuyaCategories.ASSET_NAME) raw else null
  }

  @Test
  fun `the shipped table parses into every declared category`() {
    val categories = HuyaCategories.parse(raw)

    // 4 groups and 424 sub-categories were counted from the source document.
    assertEquals(424, categories.size)
    assertTrue(categories.all { it.platform == Platform.HUYA })
  }

  @Test
  fun `every category has a numeric gid and a parent group`() {
    val categories = HuyaCategories.parse(raw)

    categories.forEach { category ->
      assertTrue(
        category.id.toLongOrNull() != null,
        "gid '${category.id}' for ${category.name} must be the numeric iGid",
      )
      assertNotNull(category.parentName, "${category.name} is missing its group")
      assertTrue(category.name.isNotBlank())
    }
  }

  @Test
  fun `category ids are unique`() {
    val ids = HuyaCategories.parse(raw).map { it.id }
    assertEquals(ids.size, ids.toSet().size)
  }

  @Test
  fun `the groups cover the expected top level tabs`() {
    val groups = HuyaCategories.parse(raw).mapNotNull { it.parentName }.distinct()

    assertEquals(
      listOf("网游竞技", "单机热游", "娱乐天地", "手游休闲"),
      groups,
    )
  }

  @Test
  fun `a known category from the table is present`() {
    val league = HuyaCategories.parse(raw).firstOrNull { it.name == "英雄联盟" }

    assertNotNull(league)
    assertEquals("1", league!!.id)
  }

  @Test
  fun `sort order is dense so the chip rows keep their source order`() {
    val orders = HuyaCategories.parse(raw).map { it.sortOrder }
    assertEquals(orders.indices.toList(), orders)
  }

  @Test
  fun `a missing asset yields no categories instead of throwing`() {
    assertTrue(HuyaCategories.load(AssetReader.Empty).isEmpty())
    assertTrue(HuyaCategories.parse(null).isEmpty())
    assertTrue(HuyaCategories.parse("not json").isEmpty())
  }
}
