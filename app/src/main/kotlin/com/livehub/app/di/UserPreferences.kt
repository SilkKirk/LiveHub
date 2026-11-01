package com.livehub.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.livehub.core.model.Platform
import com.livehub.core.sync.SyncScope
import com.livehub.core.sync.TrustedPeer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "livehub_settings")

/** User facing settings persisted with DataStore. */
@Serializable
data class UserSettings(
  val themeMode: String = "system",
  val dynamicColor: Boolean = true,
  val danmakuEnabled: Boolean = true,
  val danmakuOpacity: Float = 1.0f,
  val danmakuFontScale: Float = 1.0f,
  val danmakuAreaFraction: Float = 0.5f,
  /** Separate overlay scale for landscape/full screen; the picture is much wider there. */
  val landscapeDanmakuFontScale: Float = 1.15f,
  val blockedKeywords: List<String> = emptyList(),
  val preferredQuality: Map<String, Int> = emptyMap(),
  val autoRefreshFollows: Boolean = true,
  val lastPlatform: String = Platform.DOUYU.id,
  val syncScopes: List<String> = SyncScope.default.map { it.wireName },
  val searchHistory: List<String> = emptyList(),
  /** Serialised follow records, including tombstones. */
  val followRecords: String = "",
  /** Serialised preference records used by the sync engine. */
  val preferenceRecords: String = "",
  /** Serialised pinned-category records, including tombstones. */
  val categoryRecords: String = "",
  /**
   * Secret this device presents to paired peers.
   *
   * Peers store it verbatim and compare it on every request, so it must be stable
   * across restarts. Generated once on first pairing.
   */
  val selfSecret: String = "",
)

/**
 * Thin DataStore wrapper.
 *
 * Reads are synchronous on purpose: the app needs a few values during composition and
 * blocking on a cached value is cheaper than threading `Flow`s through every screen.
 * Writes are dispatched to the DataStore coroutine scope.
 */
class UserPreferences(private val context: Context) {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  private val keySettings = stringPreferencesKey("settings_json")
  private val keyDeviceId = longPreferencesKey("sync_device_id")
  private val keyPeers = stringPreferencesKey("sync_peers_json")

  @Volatile private var cached: UserSettings? = null

  val settings: UserSettings
    get() = cached ?: loadSettings().also { cached = it }

  private fun loadSettings(): UserSettings = runBlocking {
    val raw = context.dataStore.data.first()[keySettings]
    if (raw.isNullOrBlank()) UserSettings() else decode(raw)
  }

  private fun decode(raw: String): UserSettings =
    runCatching { json.decodeFromString(UserSettings.serializer(), raw) }.getOrDefault(UserSettings())

  fun update(transform: (UserSettings) -> UserSettings) {
    val next = transform(settings)
    cached = next
    val encoded = json.encodeToString(UserSettings.serializer(), next)
    runBlocking { context.dataStore.edit { it[keySettings] = encoded } }
  }

  // Publish (not suspend) variants used from Compose callbacks.
  fun setThemeMode(mode: String) = update { it.copy(themeMode = mode) }

  fun setDanmakuEnabled(enabled: Boolean) = update { it.copy(danmakuEnabled = enabled) }

  fun setBlockedKeywords(keywords: List<String>) = update { it.copy(blockedKeywords = keywords) }

  fun addSearchHistory(keyword: String) {
    val trimmed = keyword.trim()
    if (trimmed.isEmpty()) return
    update { current ->
      val next = (listOf(trimmed) + current.searchHistory.filterNot { it == trimmed }).take(20)
      current.copy(searchHistory = next)
    }
  }

  fun clearSearchHistory() = update { it.copy(searchHistory = emptyList()) }

  fun setLastPlatform(platform: Platform) = update { it.copy(lastPlatform = platform.id) }

  fun setPreferredQuality(platform: Platform, quality: Int) = update {
    it.copy(preferredQuality = it.preferredQuality + (platform.id to quality))
  }

  fun setSyncScopes(scopes: Set<SyncScope>) =
    update { it.copy(syncScopes = scopes.map { scope -> scope.wireName }) }

  // ------------------------------------------------------------------ identity

  val deviceId: Long
    get() = runBlocking {
      val prefs = context.dataStore.data.first()
      val existing = prefs[keyDeviceId] ?: 0L
      if (existing != 0L) {
        existing
      } else {
        val generated = generateDeviceId()
        context.dataStore.edit { it[keyDeviceId] = generated }
        generated
      }
    }

  val deviceName: String
    get() = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
      .ifBlank { "LiveHub 设备" }

  private fun generateDeviceId(): Long {
    val random = UUID.randomUUID().mostSignificantBits ushr 1
    return if (random == 0L) 1L else random
  }

  // --------------------------------------------------------------------- peers

  fun trustedPeers(): List<TrustedPeer> = runBlocking {
    val raw = context.dataStore.data.first()[keyPeers]
    if (raw.isNullOrBlank()) {
      emptyList()
    } else {
      runCatching { json.decodeFromString<List<TrustedPeer>>(raw) }.getOrDefault(emptyList())
    }
  }

  fun saveTrustedPeers(peers: List<TrustedPeer>) {
    val encoded = json.encodeToString(peers)
    runBlocking { context.dataStore.edit { it[keyPeers] = encoded } }
  }
}
