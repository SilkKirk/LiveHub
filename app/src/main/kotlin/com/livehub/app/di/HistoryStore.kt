package com.livehub.app.di

import com.livehub.core.sync.Hlc
import com.livehub.core.sync.SyncCodec
import com.livehub.core.sync.SyncRecord
import kotlinx.serialization.json.JsonObject

/**
 * The `history` sync scope: the search history, stored as one record per keyword.
 *
 * The app has kept a search history since long before the sync screen existed
 * (`UserSettings.searchHistory`, newest first, capped at [MAX_KEYWORDS]). That list is a bare
 * `List<String>`, so there is nothing in it to compare against a peer's copy: no timestamp,
 * no tombstone. Syncing it as-is would mean "the other device's list wins", which loses
 * whichever keywords the receiving device had typed most recently.
 *
 * So the history lives here as records — key = the keyword itself, value = the keyword,
 * stamped with an [Hlc] — and `searchHistory` is a **projection** of them, rewritten on every
 * change. That keeps every existing reader (`UiState.searchHistory`, the search screen's chips)
 * working unchanged while giving the merge something to order.
 */
class HistoryStore(
  private val preferences: UserPreferences,
  private val nextHlc: () -> Hlc,
  private val deviceIdProvider: () -> Long,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  /**
   * Last decoded set, together with the raw string it was decoded from.
   *
   * Keyed on the raw value rather than "have I loaded yet": `preferences.update {}` is reached
   * from outside this store (a settings reset, a factory reset, another store sharing the same
   * DataStore), and a cache that ignores that kept serving a list that no longer existed. The
   * comparison is a string pointer/length check in the common case.
   */
  @Volatile private var cachedRaw: String = ""

  @Volatile private var cachedRecords: List<SyncRecord<JsonObject>> = emptyList()

  fun records(): List<SyncRecord<JsonObject>> {
    val settings = preferences.settings
    val raw = settings.historyRecords
    if (raw.isNotEmpty() && raw == cachedRaw) return cachedRecords
    // Installations that predate this scope hold a plain list and no records. It is converted
    // in memory on first read; the records keep `Hlc.ZERO`, so any real edit — here or on a
    // peer — beats them, which is exactly right for data of unknown age.
    val records = if (raw.isEmpty()) migrated(settings.searchHistory) else decode(raw)
    cachedRaw = raw
    cachedRecords = records
    return records
  }

  /** Keywords to show, newest first. */
  fun keywords(): List<String> = display(records())

  /** Records a submitted keyword. Re-searching an old one moves it back to the top. */
  fun add(keyword: String) {
    val trimmed = keyword.trim()
    if (trimmed.isEmpty()) return
    val hlc = nextHlc()
    val now = clock()
    val deviceId = deviceIdProvider()
    preferences.update { settings ->
      val record = SyncRecord(
        key = trimmed,
        value = SyncCodec.stringValue(trimmed),
        deleted = false,
        hlc = hlc,
        deviceId = deviceId,
        updatedAt = now,
      )
      val added = current(settings).filterNot { it.key == trimmed } + record
      persist(settings, trimOverflow(added, hlc, deviceId, now))
    }
  }

  /**
   * Clears the list.
   *
   * Tombstones, not deletion: the clear has to reach the other device. Dropping the records
   * instead would make the next sync read as "this device has no history", so the peer's
   * keywords would be pulled straight back in.
   */
  fun clear() {
    val hlc = nextHlc()
    val now = clock()
    val deviceId = deviceIdProvider()
    preferences.update { settings ->
      val cleared = current(settings).map { record ->
        if (record.deleted) {
          record
        } else {
          // The type argument is explicit because `value = null` leaves `T` unconstrained; the
          // inferred `SyncRecord<out JsonObject>` then does not match the store's list type.
          SyncRecord<JsonObject>(
            key = record.key,
            value = null,
            deleted = true,
            hlc = hlc,
            deviceId = deviceId,
            updatedAt = now,
          )
        }
      }
      persist(settings, cleared)
    }
  }

  /**
   * Stores a merged record set from a peer.
   *
   * No [trimOverflow] here on purpose: the winner list is decided locally when *this* user
   * types a keyword. Trimming on merge would let a device delete a keyword the peer had just
   * sent and echo the tombstone back, so a keyword could disappear from the device that typed
   * it. The display is capped instead, which never destroys anything.
   */
  fun merge(incoming: List<SyncRecord<JsonObject>>): Int {
    if (incoming.isEmpty()) return 0
    val before = records().associate { it.key to it.version }
    preferences.update { settings ->
      var merged = current(settings)
      incoming.forEach { record -> merged = merged.filterNot { it.key == record.key } + record }
      persist(settings, merged)
    }
    return incoming.count { before[it.key] != it.version }
  }

  private fun current(settings: UserSettings): List<SyncRecord<JsonObject>> {
    val stored = decode(settings.historyRecords)
    return if (stored.isEmpty()) migrated(settings.searchHistory) else stored
  }

  private fun persist(
    settings: UserSettings,
    records: List<SyncRecord<JsonObject>>,
  ): UserSettings {
    val encoded = SyncCodec.encodeRecords(records)
    cachedRaw = encoded
    cachedRecords = records
    return settings.copy(
      historyRecords = encoded,
      searchHistory = display(records),
    )
  }

  /**
   * Turns the legacy plain list into records.
   *
   * `updatedAt` is a negative index so the stored order survives: real mutations carry epoch
   * milliseconds, which are always larger, so migrated keywords sort behind them exactly as
   * they should. `hlc` stays [Hlc.ZERO] so nothing migrated can win a conflict.
   */
  private fun migrated(old: List<String>): List<SyncRecord<JsonObject>> =
    old.filter { it.isNotBlank() }
      .distinct()
      .mapIndexed { index, keyword ->
        SyncRecord(
          key = keyword,
          value = SyncCodec.stringValue(keyword),
          deleted = false,
          hlc = Hlc.ZERO,
          deviceId = 0,
          updatedAt = -index.toLong(),
        )
      }

  /** Keeps only the newest [MAX_KEYWORDS] entries; the rest become tombstones. */
  private fun trimOverflow(
    records: List<SyncRecord<JsonObject>>,
    hlc: Hlc,
    deviceId: Long,
    now: Long,
  ): List<SyncRecord<JsonObject>> {
    val alive = records.filter { !it.deleted }
    if (alive.size <= MAX_KEYWORDS) return records
    val keep = display(alive).toSet()
    return records.map { record ->
      if (record.deleted || record.key in keep) {
        record
      } else {
        SyncRecord<JsonObject>(
          key = record.key,
          value = null,
          deleted = true,
          hlc = hlc,
          deviceId = deviceId,
          updatedAt = now,
        )
      }
    }
  }

  private fun display(records: List<SyncRecord<JsonObject>>): List<String> =
    records.filter { !it.deleted }
      .sortedWith(compareByDescending<SyncRecord<JsonObject>> { it.updatedAt }.thenBy { it.key })
      .mapNotNull { SyncCodec.readString(it.value) }
      .take(MAX_KEYWORDS)

  private fun decode(raw: String): List<SyncRecord<JsonObject>> =
    if (raw.isBlank()) emptyList() else runCatching { SyncCodec.decodeRecords(raw) }.getOrDefault(emptyList())

  companion object {
    /** Same window the plain list used: enough to be useful, short enough to stay readable. */
    const val MAX_KEYWORDS = 20
  }
}
