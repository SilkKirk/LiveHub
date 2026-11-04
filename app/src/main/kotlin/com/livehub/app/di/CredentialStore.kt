package com.livehub.app.di

import com.livehub.core.model.Platform
import com.livehub.core.sync.Hlc
import com.livehub.core.sync.SyncCodec
import com.livehub.core.sync.SyncRecord
import com.livehub.core.sync.SyncScope
import com.livehub.core.sync.SyncTarget
import com.livehub.platform.bilibili.BilibiliCookieStore
import com.livehub.platform.douyin.DouyinCookieStore
import com.livehub.platform.douyin.hasDouyinSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Bilibili and Douyin login state, as one synchronised record per platform.
 *
 * The cookie stores themselves stay the single place a request reads its credentials from;
 * this class keeps the extra metadata a merge needs — an HLC per platform and a tombstone for
 * "logged out on that device" — and mirrors every write into those stores.
 *
 * Only a *login* becomes a record: a platform with no session (Bilibili logged out, Douyin
 * holding just its anonymous `ttwid`) is stored as a tombstone, so logging out on one device
 * removes the login on the other instead of being silently refilled by it.
 *
 * The payload is not encrypted. That is a deliberate trade-off: a payload only reaches a
 * device that proved it holds this device's pairing secret, and the user asked for the login
 * to travel as part of the normal exchange. Encryption (HKDF over both pairing secrets →
 * AES-GCM) is the obvious next step if this ever leaves a trusted network.
 */
class CredentialStore(
  private val preferences: UserPreferences,
  private val bilibili: BilibiliCookieStore,
  private val douyin: DouyinCookieStore,
) {
  fun records(): List<SyncRecord<JsonObject>> {
    val raw = preferences.settings.loginRecords
    if (raw.isBlank()) return emptyList()
    return runCatching { SyncCodec.decodeRecords(raw) }.getOrDefault(emptyList())
  }

  /**
   * Records a login (or its removal) for [platform] and mirrors it into the cookie store.
   *
   * Every login action goes through here rather than straight to a cookie store, otherwise the
   * change has no clock and the other device's older record wins the next merge.
   */
  fun set(
    platform: Platform,
    header: String?,
    hlc: Hlc,
    deviceId: Long,
    now: Long,
  ) {
    val key = keyOf(platform)
    val alive = !header.isNullOrBlank()
    val record = SyncRecord(
      key = key,
      value = header?.takeIf { it.isNotBlank() }?.let { buildJsonObject { put("header", JsonPrimitive(it)) } },
      deleted = !alive,
      hlc = hlc,
      deviceId = deviceId,
      updatedAt = now,
    )
    if (alive) {
      write(platform, header.orEmpty())
    } else {
      clear(platform)
    }
    persist(records().filterNot { it.key == key } + record)
  }

  /** True when [platform] currently holds a session this app can use. */
  fun isLoggedIn(platform: Platform): Boolean = when (platform) {
    Platform.BILIBILI -> bilibili.isLoggedIn()
    Platform.DOUYIN -> hasDouyinSession(douyin.header())
    else -> false
  }

  internal fun apply(records: List<SyncRecord<out Any>>): Int {
    @Suppress("UNCHECKED_CAST")
    val typed = records as List<SyncRecord<JsonObject>>
    val before = this.records().associate { it.key to it.version }

    typed.forEach { record ->
      val platform = platformOf(record.key) ?: return@forEach
      val header = record.value?.get("header")?.jsonPrimitive?.contentOrNull
      if (record.deleted || header.isNullOrBlank()) {
        clear(platform)
      } else {
        write(platform, header)
      }
    }
    persist(typed)
    return typed.count { before[it.key] != it.version }
  }

  /** Writes a login into the cookie store the next request reads from. */
  private fun write(platform: Platform, header: String) {
    when (platform) {
      Platform.BILIBILI -> bilibili.save(header)
      Platform.DOUYIN -> douyin.save(header)
      else -> Unit
    }
  }

  private fun persist(records: List<SyncRecord<JsonObject>>) {
    val encoded = SyncCodec.encodeRecords(records)
    // The cached settings object is replaced wholesale, so this also makes the next
    // `records()` read see the new list without a DataStore round trip.
    preferences.update { it.copy(loginRecords = encoded) }
  }

  private fun clear(platform: Platform) {
    when (platform) {
      Platform.BILIBILI -> bilibili.clear()
      // Clearing the header removes the session; the next request negotiates a fresh `ttwid`.
      Platform.DOUYIN -> douyin.clear()
      else -> Unit
    }
  }

  private fun keyOf(platform: Platform): String = "login:${platform.id}"

  private fun platformOf(key: String): Platform? {
    val id = key.removePrefix("login:")
    if (id == key) return null
    return Platform.fromId(id)
  }
}

/** [SyncTarget] for the login scope. */
class CredentialSyncTarget(private val store: CredentialStore) : SyncTarget {
  override val scope: SyncScope = SyncScope.CREDENTIALS

  override fun snapshot(): List<SyncRecord<out Any>> = store.records()

  override fun apply(records: List<SyncRecord<out Any>>): Int = store.apply(records)
}
