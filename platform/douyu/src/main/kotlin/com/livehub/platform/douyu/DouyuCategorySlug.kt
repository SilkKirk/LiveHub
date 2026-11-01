package com.livehub.platform.douyu

/**
 * Maps a Douyu `cate2Id` to the slug used by `www.douyu.com/directory/game/<slug>`.
 *
 * ## Why this is no longer a hand-written table
 *
 * The first version guessed slugs (`"86" to "LOL"`, `"2" to "WZRY"`, …) from a handful of
 * known categories and fell back to the numeric id for everything else. Two things were
 * wrong with that:
 *
 *  * the numeric id is **not** a valid slug — `/directory/game/86` answers 404, so every
 *    unmapped category failed with a network error;
 *  * several guesses were simply wrong (`cate2Id 1` is 英雄联盟, not 86).
 *
 * The category endpoint already publishes the real slug as `shortName`
 * (`英雄联盟 -> "LOL"`, `王者荣耀 -> "wzry"`, `一起看 -> "yqk"`), verified against 12
 * categories across four parent groups. [DouyuParser.categories] therefore carries the
 * slug through, and this object only holds the static fallbacks needed when a slug is
 * missing — for example the hand-built list used before categories have loaded.
 */
object DouyuCategorySlug {

  /**
   * Fallbacks for the categories the UI can open before `cate/list` has been fetched.
   *
   * Keys are real `cate2Id` values; values are the slug Douyu publishes as `shortName`.
   */
  private val fallbacks = mapOf(
    "1" to "LOL",
    "181" to "wzry",
    "1920" to "LOLM",
    "1092" to "mdnf",
    "3" to "DNF",
    "194" to "ms",
    "208" to "yqk",
    "725" to "DMC",
    "375" to "qqfcdy",
    "1166" to "fkjy",
    "1174" to "yxl",
    "1140" to "RichMan",
  )

  /**
   * Slug for [categoryId].
   *
   * @param knownSlug the `shortName` from `cate/list`, which always wins.
   * @return the real slug, or null when nothing usable is known — callers should then
   *   report a readable error instead of requesting a URL that is guaranteed to 404.
   */
  fun forId(categoryId: String, knownSlug: String? = null): String? {
    val trimmed = knownSlug?.trim().orEmpty()
    if (trimmed.isNotEmpty()) return trimmed
    return fallbacks[categoryId]
  }

  /** Reverse lookup used by tests and diagnostics. */
  fun idFor(slug: String): String? =
    fallbacks.entries.firstOrNull { it.value.equals(slug, ignoreCase = true) }?.key
}
