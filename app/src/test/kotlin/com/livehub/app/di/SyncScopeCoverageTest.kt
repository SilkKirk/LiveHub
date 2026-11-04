package com.livehub.app.di

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.core.sync.Hlc
import com.livehub.core.sync.SyncCodec
import com.livehub.core.sync.SyncRecord
import com.livehub.core.sync.SyncScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pins the three scopes behind the sync screen's "同步内容" chips.
 *
 * Two of them used to be decoration: `history` had no [com.livehub.core.sync.SyncTarget] at
 * all, and `preferences` had one whose `snapshot` only ever returned records that had *arrived*
 * from another device — nothing ever wrote one, so the outbound payload was always empty. A chip
 * that does nothing is worse than a missing feature: the user is told the setting follows them
 * and the other device silently keeps its own state.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SyncScopeCoverageTest {

  private lateinit var container: AppContainer
  private var now = 10_000L

  @Before
  fun setUp() {
    container = AppContainer(ApplicationProvider.getApplicationContext())
    // DataStore is a process-wide file, so a previous test's settings would leak in.
    container.preferences.update { UserSettings() }
    container.followStore.replaceAll(emptyList())
  }

  /** The one assertion that would have caught the missing `history` scope. */
  @Test
  fun `every scope offered on the sync screen has a target`() {
    val missing = SyncScope.entries.filter { container.syncTargets.targetFor(it) == null }

    assertTrue(
      "the sync screen renders a chip per scope, so these would do nothing: $missing",
      missing.isEmpty(),
    )
  }

  /**
   * A local change has to exist as a record before it can be sent.
   *
   * This is the exact regression: the switch was on, the user turned danmaku off, and the
   * payload built for the tablet carried zero records because nothing on this device ever
   * created one.
   */
  @Test
  fun `changing a danmaku setting produces a record`() {
    container.preferencesStore.setFlag(PreferenceKeys.DANMAKU_ENABLED, false)

    val payload = container.syncEngine.buildPayload(SyncScope.PREFERENCES)
    assertNotNull("the preferences scope must have something to send", payload)
    val records = SyncCodec.decodeRecords(payload!!.entries)
    val record = records.firstOrNull { it.key == PreferenceKeys.DANMAKU_ENABLED }

    assertNotNull("danmakuEnabled must be part of the payload", record)
    assertEquals(false, SyncCodec.readFlag(record!!.value))
    assertTrue("a local edit must carry this device's clock", record.hlc > Hlc.ZERO)
  }

  /** Preferences are one record per value, so the numeric sliders travel too. */
  @Test
  fun `numeric settings travel as their own records`() {
    container.preferencesStore.setNumber(PreferenceKeys.DANMAKU_OPACITY, 0.42f)
    container.preferencesStore.setInt(PreferenceKeys.QUALITY_PREFIX + "douyu", 3)

    val records = SyncCodec.decodeRecords(
      container.syncEngine.buildPayload(SyncScope.PREFERENCES)!!.entries,
    )

    assertEquals(2, records.size)
    assertEquals(0.42f, readFloat(records, PreferenceKeys.DANMAKU_OPACITY)!!, 1e-4f)
    assertEquals(3, SyncCodec.readInt(records.first { it.key.endsWith("douyu") }.value))
  }

  /** What the peer sends has to reach the field the UI renders. */
  @Test
  fun `a preference from the other device changes the setting`() {
    val result = container.syncEngine.applyPayload(
      preferencesPayload(
        record(PreferenceKeys.DANMAKU_FONT_SCALE, buildJsonObject { put("v", JsonPrimitive(1.6f)) }),
      ),
      peer = null,
    )

    assertTrue(result.accepted)
    assertEquals(1.6f, container.preferences.settings.danmakuFontScale, 1e-4f)
  }

  /** …and must not be sent straight back, or every sync would move the same records forever. */
  @Test
  fun `an applied preference is not echoed back`() {
    val remote = record(
      PreferenceKeys.DANMAKU_OPACITY,
      buildJsonObject { put("v", JsonPrimitive(0.35f)) },
    )
    container.syncEngine.applyPayload(preferencesPayload(remote), peer = null)

    val delta = container.syncEngine.buildPayloads(SyncScope.PREFERENCES, peerKnown = listOf(remote))

    assertEquals(0, delta.single().recordCount)
  }

  /** An out-of-range value from a peer must not make the overlay invisible. */
  @Test
  fun `an out of range remote value is clamped`() {
    val tooBig = buildJsonObject { put("v", JsonPrimitive(9f)) }
    container.syncEngine.applyPayload(
      preferencesPayload(record(PreferenceKeys.DANMAKU_OPACITY, tooBig)),
      peer = null,
    )

    assertEquals(
      PreferenceLimits.OPACITY.endInclusive,
      container.preferences.settings.danmakuOpacity,
      1e-4f,
    )
  }

  /** A submitted keyword is what the history scope exists for. */
  @Test
  fun `a submitted keyword becomes a history record`() {
    container.preferences.setSyncScopes(setOf(SyncScope.HISTORY))
    now += 1_000
    historyStore().add("  一条小团团 ")

    val records = SyncCodec.decodeRecords(
      container.syncEngine.buildPayload(SyncScope.HISTORY)!!.entries,
    )

    assertEquals(1, records.size)
    assertEquals("一条小团团", records.first().key)
    assertEquals(listOf("一条小团团"), historyStore().keywords())
  }

  /** What the other device searched for shows up here, in the order the screen uses. */
  @Test
  fun `a keyword from the other device appears in the history`() {
    container.preferences.setSyncScopes(setOf(SyncScope.HISTORY))
    now += 1_000
    val store = historyStore()
    store.add("主播甲")

    val remote = SyncRecord(
      key = "主播乙",
      value = SyncCodec.stringValue("主播乙"),
      deleted = false,
      hlc = Hlc(now + 5_000, 0),
      deviceId = 99L,
      updatedAt = now + 5_000,
    )
    container.syncEngine.applyPayload(
      SyncCodec.buildPayload(SyncScope.HISTORY, listOf(remote), deviceId = 99, sequence = 1, now = now + 5_000),
      peer = null,
    )

    assertEquals(listOf("主播乙", "主播甲"), store.keywords())
  }

  /**
   * Clearing adds tombstones rather than dropping records.
   *
   * Without them the peer's copy would be pulled straight back on the next sync: "this device
   * knows nothing about that keyword" is indistinguishable from "this device never had it".
   */
  @Test
  fun `clearing the history leaves tombstones that travel`() {
    container.preferences.setSyncScopes(setOf(SyncScope.HISTORY))
    now += 1_000
    val store = historyStore()
    store.add("广告")
    now += 1_000
    store.add("刷屏")

    store.clear()

    assertTrue(store.keywords().isEmpty())
    val records = SyncCodec.decodeRecords(
      container.syncEngine.buildPayload(SyncScope.HISTORY)!!.entries,
    )
    assertEquals(2, records.size)
    assertTrue("both keywords must travel as deletes", records.all { it.deleted })
  }

  /** The window is 20, like the plain list it replaced, and the loss is a tombstone. */
  @Test
  fun `the history keeps the newest twenty keywords`() {
    val store = historyStore()
    (1..21).forEach { index ->
      now += 1_000
      store.add("词$index")
    }

    val keywords = store.keywords()

    assertEquals(HistoryStore.MAX_KEYWORDS, keywords.size)
    assertEquals("词21", keywords.first())
    assertEquals("词2", keywords.last())
    assertTrue(
      "词1 must be dropped as a tombstone so the delete can propagate",
      store.records().any { it.key == "词1" && it.deleted },
    )
  }

  /** Re-searching an old keyword moves it back to the top instead of duplicating it. */
  @Test
  fun `researching a keyword moves it to the top`() {
    val store = historyStore()
    now += 1_000
    store.add("甲")
    now += 1_000
    store.add("乙")
    now += 1_000
    store.add("甲")

    assertEquals(listOf("甲", "乙"), store.keywords())
  }

  /** Records written before this scope existed are converted, not lost. */
  @Test
  fun `a legacy plain list is migrated into synced records`() {
    container.preferences.update {
      it.copy(searchHistory = listOf("旧词一", "旧词二"), historyRecords = "")
    }

    val store = historyStore()

    assertEquals(listOf("旧词一", "旧词二"), store.keywords())
    assertEquals(2, store.records().size)
    assertTrue(
      "migrated entries must lose against any real edit",
      store.records().all { it.hlc == Hlc.ZERO },
    )
    // Reading must not write: the migration happens in memory until something changes.
    assertEquals("", container.preferences.settings.historyRecords)
  }

  // ------------------------------------------------------------------- helpers ---

  private fun historyStore(): HistoryStore = HistoryStore(
    preferences = container.preferences,
    nextHlc = { container.syncEngine.nextHlc() },
    deviceIdProvider = { 7L },
    clock = { now },
  )

  private fun record(key: String, value: JsonObject): SyncRecord<JsonObject> = SyncRecord(
    key = key,
    value = value,
    deleted = false,
    hlc = Hlc(now + 1_000, 0),
    deviceId = 99L,
    updatedAt = now + 1_000,
  )

  private fun preferencesPayload(vararg records: SyncRecord<JsonObject>) = SyncCodec.buildPayload(
    scope = SyncScope.PREFERENCES,
    records = records.toList(),
    deviceId = 99,
    sequence = 1,
    now = now + 1_000,
  )

  private fun readFloat(records: List<SyncRecord<JsonObject>>, key: String): Float? =
    records.firstOrNull { it.key == key }
      ?.value?.get("v")?.let { (it as? JsonPrimitive)?.content?.toFloatOrNull() }
}
