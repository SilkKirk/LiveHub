package com.livehub.platform.douyu

/**
 * Maps a Douyu `cate2Id` to the slug used by `www.douyu.com/directory/game/<slug>`.
 *
 * The directory page is keyed by a human readable slug (`LOL`, `DNF`, …) rather than
 * the numeric category id returned by `/api/cate/list`. Only the popular categories are
 * mapped explicitly; anything else falls back to the numeric id, which Douyu also
 * accepts and which redirects to the right slug.
 */
object DouyuCategorySlug {

  private val slugs = mapOf(
    "1" to "LOL",
    "2" to "WZRY",
    "3" to "DNF",
    "4" to "PUBG",
    "5" to "How",
    "6" to "yz",
    "7" to "Blizzard",
    "8" to "mobilegame",
    "9" to "Stand-alone",
    "10" to "warcraft3",
    "13" to "Dota2",
    "14" to "CF",
    "17" to "fifa",
    "22" to "nba2k",
    "26" to "qqfeiche",
    "28" to "mszb",
    "31" to "lscs",
    "33" to "qhyp",
    "36" to "csgo",
    "40" to "Game",
    "55" to "lol",
    "65" to "music",
    "66" to "talk",
    "70" to "yz",
    "81" to "yanzhi",
    "86" to "LOL",
    "88" to "wzry",
    "101" to "hy",
    "110" to "jdqs",
    "113" to "mhxy",
    "116" to "zhw",
    "120" to "cq",
    "124" to "hszb",
    "131" to "hxcq",
    "159" to "wzq",
    "166" to "h5game",
    "174" to "sp",
    "181" to "wzry",
    "203" to "pd",
    "208" to "txjh",
    "217" to "dhxy",
    "220" to "huwai",
    "233" to "yys",
    "237" to "esport",
    "250" to "lscs",
    "259" to "yhk",
    "270" to "tx3",
    "274" to "cqyx",
    "283" to "danji",
    "288" to "acg",
    "295" to "hy",
    "309" to "yule",
    "320" to "hearthstone",
    "322" to "nz",
    "331" to "star",
    "340" to "jdqs",
    "346" to "zhibo",
    "355" to "self",
    "371" to "radio",
    "387" to "root",
    "416" to "cf",
    "429" to "yz",
    "451" to "hy",
    "461" to "wzry",
    "480" to "jdqs",
    "508" to "hearthstone",
    "520" to "huwai",
    "532" to "yqk",
    "575" to "lscs",
    "658" to "ys",
    "712" to "hy",
    "800" to "lscs",
    "910" to "lscs",
    "978" to "yyjj",
    "1092" to "DNFmobile",
    "1145" to "yz",
    "1256" to "lscs",
    "2793" to "hepingjingying",
  )

  /** Slug for [categoryId], or the id itself when unmapped. */
  fun forId(categoryId: String): String = slugs[categoryId] ?: categoryId

  /** Reverse lookup used when a page reports its own slug. */
  fun idFor(slug: String): String? =
    slugs.entries.firstOrNull { it.value.equals(slug, ignoreCase = true) }?.key
}
