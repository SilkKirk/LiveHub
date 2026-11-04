package com.livehub.app.di

import com.livehub.core.model.Platform
import com.livehub.core.sync.Hlc
import com.livehub.core.sync.SyncCodec
import com.livehub.core.sync.SyncRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Record keys used by the `preferences` sync scope. */
object PreferenceKeys {
  const val DANMAKU_ENABLED = "danmakuEnabled"
  const val DANMAKU_OPACITY = "danmakuOpacity"
  const val DANMAKU_FONT_SCALE = "danmakuFontScale"
  const val DANMAKU_AREA_FRACTION = "danmakuAreaFraction"
  const val LANDSCAPE_DANMAKU_FONT_SCALE = "landscapeDanmakuFontScale"
  const val BLOCKED_KEYWORDS = "blockedKeywords"

  /** One record per platform, e.g. `quality:douyu` = 0 (原画). */
  const val QUALITY_PREFIX = "quality:"
}

/**
 * Accepted ranges for the danmaku sliders.
 *
 * They live next to the store rather than in the ViewModel because the same bounds have to
 * be applied twice: once where the user drags a slider, and once when a value arrives from
 * the other device. A peer running an older or newer build can send anything, and a value
 * outside these ranges makes the overlay either invisible or larger than the picture.
 */
object PreferenceLimits {
  val OPACITY = 0.2f..1f
  val FONT_SCALE = 0.6f..2f
  val AREA_FRACTION = 0.1f..1f
  val LANDSCAPE_FONT_SCALE = 0.8f..2f
}

/**
 * The `preferences` sync scope: danmaku and playback settings, stored as synchronised records.
 *
 * Before this class existed the scope had **no writer at all**. `UserSettings.preferenceRecords`
 * was read by [PreferencesSyncTarget.snapshot] and written by its `apply`, so the only records
 * that ever existed were the ones that had arrived from another device: a setting the user
 * changed on this phone never produced a record, the payload built for the tablet was always
 * empty, and the "播放与弹幕设置" switch could not do what its label promised.
 *
 * Two representations exist on purpose:
 *  * [UserSettings] holds the plain values the UI reads;
 *  * `preferenceRecords` holds the same values **with** their [Hlc], which is what the sync
 *    engine needs to decide a conflict.
 * Every mutation goes through one of the two private entry points below, each of which writes
 * both representations inside a single `update {}`, so they cannot drift.
 */
class PreferencesStore(
  private val preferences: UserPreferences,
  private val nextHlc: () -> Hlc,
  private val deviceIdProvider: () -> Long,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun records(): List<SyncRecord<JsonObject>> = decode(preferences.settings.preferenceRecords)

  fun setFlag(key: String, value: Boolean) = write(key, SyncCodec.flagValue(value))

  fun setNumber(key: String, value: Float) = write(key, numberValue(value))

  fun setInt(key: String, value: Int) = write(key, SyncCodec.intValue(value))

  fun setStringList(key: String, values: List<String>) = write(key, SyncCodec.stringListValue(values))

  /**
   * Stores a merged record set that came from a peer.
   *
   * [records] is already the output of `SyncMerge`, i.e. the union of both sides with the
   * losing versions removed, so each key replaces whatever this device held. Tombstones are
   * kept (they are what stops a deleted preference from coming back) but they are not applied
   * to the visible settings: a preference has no "deleted" value to show, and reverting a
   * slider to a default the user never chose would be worse than leaving it alone.
   */
  fun applyRemote(records: List<SyncRecord<JsonObject>>): Int {
    if (records.isEmpty()) return 0
    val before = this.records().associate { it.key to it.version }
    preferences.update { settings ->
      val merged = upsert(decode(settings.preferenceRecords), records)
      val applied = records
        .filterNot { it.deleted }
        .fold(settings) { acc, record -> acc.withPreference(record.key, record.value) }
      applied.copy(preferenceRecords = SyncCodec.encodeRecords(merged))
    }
    return records.count { before[it.key] != it.version }
  }

  /** The single local write path: fresh [Hlc], one record, both representations. */
  private fun write(key: String, value: JsonObject) {
    val hlc = nextHlc()
    val now = clock()
    val deviceId = deviceIdProvider()
    preferences.update { settings ->
      val record = SyncRecord(
        key = key,
        value = value,
        deleted = false,
        hlc = hlc,
        deviceId = deviceId,
        updatedAt = now,
      )
      settings.withPreference(key, value)
        .copy(preferenceRecords = SyncCodec.encodeRecords(upsert(decode(settings.preferenceRecords), listOf(record))))
    }
  }

  private fun upsert(
    current: List<SyncRecord<JsonObject>>,
    incoming: List<SyncRecord<JsonObject>>,
  ): List<SyncRecord<JsonObject>> {
    var result = current
    incoming.forEach { record -> result = result.filterNot { it.key == record.key } + record }
    return result
  }

  private fun decode(raw: String): List<SyncRecord<JsonObject>> =
    if (raw.isBlank()) emptyList() else runCatching { SyncCodec.decodeRecords(raw) }.getOrDefault(emptyList())

  private fun numberValue(value: Float): JsonObject = buildJsonObject { put("v", JsonPrimitive(value)) }

  private fun readNumber(value: JsonObject?): Float? =
    value?.get("v")?.jsonPrimitive?.content?.toFloatOrNull()

  /** The one place that knows which record key maps to which field of [UserSettings]. */
  private fun UserSettings.withPreference(key: String, value: JsonObject?): UserSettings = when {
    key == PreferenceKeys.DANMAKU_ENABLED ->
      copy(danmakuEnabled = SyncCodec.readFlag(value) ?: danmakuEnabled)

    key == PreferenceKeys.DANMAKU_OPACITY ->
      copy(danmakuOpacity = (readNumber(value) ?: danmakuOpacity).coerceIn(PreferenceLimits.OPACITY))

    key == PreferenceKeys.DANMAKU_FONT_SCALE ->
      copy(danmakuFontScale = (readNumber(value) ?: danmakuFontScale).coerceIn(PreferenceLimits.FONT_SCALE))

    key == PreferenceKeys.DANMAKU_AREA_FRACTION ->
      copy(
        danmakuAreaFraction = (readNumber(value) ?: danmakuAreaFraction)
          .coerceIn(PreferenceLimits.AREA_FRACTION),
      )

    key == PreferenceKeys.LANDSCAPE_DANMAKU_FONT_SCALE ->
      copy(
        landscapeDanmakuFontScale = (readNumber(value) ?: landscapeDanmakuFontScale)
          .coerceIn(PreferenceLimits.LANDSCAPE_FONT_SCALE),
      )

    // An empty list is a real value here: it means the user removed every blocked word.
    key == PreferenceKeys.BLOCKED_KEYWORDS ->
      copy(blockedKeywords = SyncCodec.readStringList(value))

    key.startsWith(PreferenceKeys.QUALITY_PREFIX) -> {
      val platform = Platform.fromId(key.removePrefix(PreferenceKeys.QUALITY_PREFIX))
      val code = SyncCodec.readInt(value)
      if (platform == null || code == null) {
        this
      } else {
        copy(preferredQuality = preferredQuality + (platform.id to code))
      }
    }

    else -> this
  }
}
