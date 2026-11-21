package com.livehub.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * This device can be named, and the name survives a restart.
 *
 * It could not before: `deviceName` was `Build.MANUFACTURER + " " + Build.MODEL`, computed on every
 * read. Two of the same phone therefore appeared under the same string in each other's 我的设备, and
 * the only way to tell them apart was the pairing timestamp — while the page's one destructive action
 * (解除关联) sat on those indistinguishable rows.
 *
 * The three-layer resolution is what these pin: typed name, then the model string, then
 * [UNNAMED_DEVICE]. Blank is meaningful — it is "no override", which is what the dialog's empty field
 * produces — so a test that only asserted "a name comes back" would pass for an install that had
 * silently forgotten the rename.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceNameTest {

  private val context get() = ApplicationProvider.getApplicationContext<Context>()

  private lateinit var preferences: UserPreferences

  @Before
  fun setUp() {
    preferences = UserPreferences(context)
    // DataStore is a process-wide file, so a rename from another test would leak in.
    preferences.setDeviceName("")
    preferences.awaitWrites()
  }

  /** What an installation that has never renamed anything shows: whatever `Build` reports. */
  private fun defaultName(): String = UserPreferences(context).deviceName

  @Test
  fun `a fresh install is named after its model`() {
    val name = defaultName()

    assertTrue("a device name must never be blank", name.isNotBlank())
    assertEquals(
      "the two Build fields joined with one space",
      listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { UNNAMED_DEVICE },
      name,
    )
  }

  @Test
  fun `a rename survives a restart`() {
    preferences.setDeviceName("我的平板")
    preferences.awaitWrites()

    // A cold start shares nothing with the old instance except the DataStore file.
    val restarted = UserPreferences(context)

    assertEquals("我的平板", restarted.deviceName)
  }

  /** The dialog's empty field is not a no-op: it drops the override. */
  @Test
  fun `clearing the name restores the model string`() {
    val before = defaultName()

    preferences.setDeviceName("我的平板")
    assertNotEquals("the premise: the rename took", before, preferences.deviceName)

    preferences.setDeviceName("   ")

    assertEquals("a cleared name goes back to the model", before, preferences.deviceName)
  }

  @Test
  fun `the effective name is normalised, not the raw input`() {
    preferences.setDeviceName("  我的   平板  ")

    assertEquals("trims and collapses", "我的 平板", preferences.deviceName)
  }

  // ------------------------------------------------------------- normalisation ---

  @Test
  fun `a name is trimmed and its whitespace runs collapsed`() {
    assertEquals("我的 平板", normaliseDeviceName("  我的\t\t平板  "))
    assertEquals("Pixel 7", normaliseDeviceName("Pixel\n7"))
  }

  @Test
  fun `a blank name means no override`() {
    assertEquals("", normaliseDeviceName("   "))
    assertEquals("", normaliseDeviceName("\t\n"))
  }

  /**
   * A long name is cut by code point, not by `String.length`.
   *
   * Half a surrogate pair is not text: kotlinx.serialization would write a lone surrogate into the
   * settings record, and the string the *other* device renders would be a replacement character. The
   * emoji here is one code point and two chars, so a `take(24)` implementation fails this.
   */
  @Test
  fun `a long name is capped without splitting a surrogate pair`() {
    val emoji = "😀"
    val normalised = normaliseDeviceName(emoji.repeat(30))

    assertEquals(
      "the cap counts code points",
      MAX_DEVICE_NAME_LENGTH,
      normalised.codePointCount(0, normalised.length),
    )
    assertEquals("whole emoji, not half of one", emoji.repeat(MAX_DEVICE_NAME_LENGTH), normalised)
  }
}
