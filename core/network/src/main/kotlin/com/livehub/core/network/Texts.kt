package com.livehub.core.network

/** Small text helpers shared by the platform adapters. */
object Texts {
  private val HTML_ENTITIES = mapOf(
    "&amp;" to "&",
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&apos;" to "'",
    "&#39;" to "'",
    "&nbsp;" to " ",
  )

  private val EM_TAG = Regex("(?i)</?em[^>]*>")

  /** Decodes the handful of entities the streaming sites actually emit. */
  fun decodeHtmlEntities(value: String): String {
    if (value.isEmpty()) return value
    var result = value
    HTML_ENTITIES.forEach { (entity, replacement) ->
      result = result.replace(entity, replacement)
    }
    return result
  }

  /** Removes the `<em class="...">` highlight markup Bilibili wraps around matches. */
  fun stripHighlightTags(value: String): String =
    if (value.contains("<em", ignoreCase = true)) EM_TAG.replace(value, "") else value

  /** Normalises protocol-relative and scheme-less URLs into absolute https URLs. */
  fun normalizeUrl(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    return when {
      value.startsWith("http://") || value.startsWith("https://") -> value
      value.startsWith("//") -> "https:$value"
      else -> "https://$value"
    }
  }
}

/** Top level alias so platform modules can call `decodeHtmlEntities(...)` directly. */
fun decodeHtmlEntities(value: String): String = Texts.decodeHtmlEntities(value)
