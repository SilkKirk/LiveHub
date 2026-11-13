package com.livehub.app.ui.screens

import com.livehub.core.designsystem.LiveHubTypography
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The type scale has a floor, and this is where it is written down.
 *
 * `LiveHubTypography` set `labelSmall` to 10sp — one step below Material's own 11sp for the style —
 * and left `bodySmall` unset, so it inherited Material's 12sp. Those two are not decorative: they
 * carry the card's sub-labels (未开播, the audience figure, the partition badge), the sync page's
 * notes, and the portrait danmaku body. At 320dp, 10sp Chinese is where legibility ends — the audit
 * that opened this round could not read the 未开播 pill — so 11sp is the smallest size the app may
 * draw text at, and `bodySmall`, the style that carries running text, sits above it.
 *
 * The assertions read `LiveHubTypography` directly instead of composing a screen: a render only
 * covers the styles the fixture happens to use, and the failure would arrive as "some pixel moved"
 * rather than as "the floor was lowered".
 *
 * They are floors, not equalities, so a later round may raise either size without editing this file;
 * they fail the moment a size drops back below the line. The scale is absolute `sp` throughout, so
 * `value` is compared as-is.
 *
 * JUnit 4 (`org.junit`), like the rest of `:app`'s unit tests; `:core:designsystem` is on JUnit 5,
 * which is why a rule about its type scale lives here.
 */
class TypographyFloorTest {

  @Test
  fun `labelSmall never drops below 11sp`() {
    val size = LiveHubTypography.labelSmall.fontSize.value

    assertTrue(
      "labelSmall is ${size}sp; the floor is 11sp because 320dp Chinese below it is unreadable",
      size >= 11f,
    )
  }

  @Test
  fun `bodySmall never drops below 13sp`() {
    val size = LiveHubTypography.bodySmall.fontSize.value

    assertTrue(
      "bodySmall is ${size}sp; it carries the danmaku body and has to stay above labelSmall",
      size >= 13f,
    )
  }

  /**
   * The leading is part of the rule, not a detail of it.
   *
   * What this round fixed was a size *and* a box: `labelSmall` was 10sp on 14sp of leading, and
   * `bodySmall` had no override at all, so it kept Material's 16sp box for a 12sp font. Raising the
   * font size without raising the leading makes text harder to read, not easier, so both boxes are
   * pinned too — a change that touches only `fontSize` fails here.
   */
  @Test
  fun `the small styles keep their leading`() {
    val label = LiveHubTypography.labelSmall.lineHeight.value
    val body = LiveHubTypography.bodySmall.lineHeight.value

    assertTrue("labelSmall has ${label}sp of leading; the floor is 16sp", label >= 16f)
    assertTrue("bodySmall has ${body}sp of leading; the floor is 18sp", body >= 18f)
  }
}
