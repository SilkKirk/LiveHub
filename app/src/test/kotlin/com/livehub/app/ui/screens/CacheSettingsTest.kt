package com.livehub.app.ui.screens

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.designsystem.LiveHubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The 图片缓存 card and the formatter it prints.
 *
 * Two things are worth pinning here. `formatCacheSize` decides strings a user reads, and the
 * boundaries are where a size readout lies: `0 B` is not `0.0 B`, `999 B` is not `1.0 KB`, and a
 * failed read has to show `—` rather than `0 B`, which would look like a successful measurement of
 * an empty cache. The card itself has no other seam — Coil's caches are a process-wide singleton —
 * so the honest assertions are "rendering the page does not crash", "the control is reachable",
 * and "clearing sends exactly one snackbar".
 *
 * The page is rendered inside [LiveHubTheme], as every screen test in this package has been since
 * round 85: these assertions describe a real `SettingsScreen`, and the `scrollTo` calls below are
 * statements about where the fold falls on a 320dp-wide page — a height that comes from the app's
 * type scale rather than from Material's defaults. The 48dp target is floored inside the card
 * itself, so it is the scroll, not the button, that a type-scale change would move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class CacheSettingsTest {

  @get:Rule
  val composeRule = createComposeRule()

  /** The settings page is a `LazyColumn`, so a card below the fold is only composed once scrolled to. */
  private fun renderSettings(actions: MutableList<UiAction>) {
    composeRule.setContent {
      LiveHubTheme {
        SettingsScreen(state = UiState(screen = Screen.Settings), onAction = { actions += it })
      }
    }
    composeRule.waitForIdle()
  }

  private fun scrollTo(text: String) {
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
  }

  /**
   * Idles until an action arrives, or fails.
   *
   * The clear runs on `Dispatchers.IO`, so the message is sent only after a real background hop
   * and a resume on the main looper. A single `waitForIdle()` observes the state from *before* that
   * resume — the trap `MainViewModelTest.awaitCondition` documents — so the loop drains the looper
   * through both paths and gives the background thread wall-clock time to finish.
   */
  private fun awaitAction(actions: List<UiAction>, timeoutMillis: Long = 5_000) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
      shadowOf(Looper.getMainLooper()).idle()
      composeRule.waitForIdle()
      if (actions.isNotEmpty()) return
      Thread.sleep(10)
    }
    assertTrue("no action arrived within ${timeoutMillis}ms", actions.isNotEmpty())
  }

  // ------------------------------------------------------------------- formatting ---

  @Test
  fun `an empty cache reads as plain zero bytes`() {
    assertEquals("0 bytes must not be formatted as a rounded unit", "0 B", formatCacheSize(0L))
  }

  @Test
  fun `a count below the first unit stays in bytes`() {
    assertEquals("999 bytes is 999 B, not 1.0 KB", "999 B", formatCacheSize(999L))
  }

  @Test
  fun `the kilobyte step is 1024 bytes`() {
    assertEquals("1024 bytes is one kilobyte", "1.0 KB", formatCacheSize(1_024L))
    assertEquals("1536 bytes is one and a half kilobytes", "1.5 KB", formatCacheSize(1_536L))
  }

  @Test
  fun `megabytes keep one decimal`() {
    // 12.4 MB in whole bytes: the truncation still has to format back to 12.4.
    assertEquals(
      "megabytes keep one decimal",
      "12.4 MB",
      formatCacheSize((12.4 * 1024 * 1024).toLong()),
    )
  }

  @Test
  fun `gigabytes keep the trailing decimal`() {
    assertEquals(
      "3 GB has to read like the units below it",
      "3.0 GB",
      formatCacheSize(3L * 1024 * 1024 * 1024),
    )
  }

  // ------------------------------------------------------------------- the card ---

  @Test
  fun `the image cache card is reachable on a small screen`() {
    renderSettings(mutableListOf())

    scrollTo("图片缓存")
    composeRule.onNodeWithText("图片缓存").assertIsDisplayed()

    scrollTo("清除缓存")
    composeRule.onNodeWithText("清除缓存").assertIsDisplayed()
  }

  /**
   * The touch target, measured rather than assumed.
   *
   * This project has shipped a sub-48dp target twice, both times behind a modifier that reported
   * 48dp on its outer node, so the size of the node a finger actually hits is what gets asserted.
   */
  @Test
  fun `the clear button is at least 48dp tall`() {
    renderSettings(mutableListOf())

    scrollTo("清除缓存")
    val heightPx = composeRule.onNodeWithText("清除缓存").fetchSemanticsNode().size.height
    val heightDp = with(composeRule.density) { heightPx.toDp().value }

    assertTrue("the clear button is ${heightDp}dp tall, below the 48dp minimum", heightDp >= 48f)
  }

  @Test
  fun `clearing the cache sends exactly one snackbar`() {
    val actions = mutableListOf<UiAction>()
    renderSettings(actions)

    scrollTo("清除缓存")
    composeRule.onNodeWithText("清除缓存").performClick()
    awaitAction(actions)

    val messages = actions.filterIsInstance<UiAction.ShowMessage>()
    assertEquals("expected exactly one snackbar, got: $actions", 1, messages.size)
    assertEquals("the snackbar text", "已清除图片缓存", messages.single().text)
  }
}
