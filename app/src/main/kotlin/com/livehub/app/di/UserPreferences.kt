package com.livehub.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.livehub.core.model.Platform
import com.livehub.core.sync.DiscoveredPeer
import com.livehub.core.sync.TrustedPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
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
  /** Keywords the search screen shows, newest first. Local only — never synchronised. */
  val searchHistory: List<String> = emptyList(),
  /**
   * Serialised follow records, including tombstones: one record per followed room.
   *
   * The wire name is still `libraryRecords` because that is what versions 2 shipped. Renaming the
   * key would decode every existing installation as "no follows at all" — an internal cleanup must
   * not silently empty a user's follow list, so the old name stays and only the Kotlin property is
   * renamed.
   */
  @SerialName("libraryRecords")
  val followRecords: String = "",
  /**
   * Which record vocabulary [followRecords] is written in.
   *
   * 1 = bare `<platform>:<roomId>` keys. 2 = prefixed keys with four record kinds
   * (`room:` / `group:` / `member:` / `fav:`). 3 = `room:` only again; [FollowStore] rewrites
   * versions 1 and 2 on first read and then writes 3.
   */
  val recordsVersion: Int = 3,
  /**
   * Serialised login-state records (one per platform), including tombstones.
   *
   * Separate from the cookie stores themselves so each platform keeps working without a
   * record (a hand-typed cookie, an anonymous `ttwid`), while the HLC metadata that decides
   * "which device logged in last" lives in one place.
   */
  val loginRecords: String = "",
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
 * blocking on a cached value is cheaper than threading `Flow`s through every screen. The first
 * read of [settings] and of [deviceId] touches DataStore once and is then cached; every later
 * read is a field access.
 *
 * Writes are **not** synchronous. They are serialised onto [writeScope] so a click handler never
 * blocks on a file write; see [update]. `saveTrustedPeers` and `savePeerAddresses` still write on
 * the caller's thread, because every caller of those is already on a background dispatcher
 * (`SyncController` runs its whole exchange on `Dispatchers.IO`).
 */
class UserPreferences(private val context: Context) {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  /**
   * One worker thread, not a pool.
   *
   * [update] is a read-modify-write over the whole settings record, so concurrent writes could
   * otherwise be applied out of order and let an older value win. Serialising them costs nothing:
   * the writes are small and there is at most one per user gesture.
   */
  private val writeScope = CoroutineScope(
    SupervisorJob() + java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "livehub-preferences-write").apply { isDaemon = true }
    }.asCoroutineDispatcher(),
  )

  private val keySettings = stringPreferencesKey("settings_json")
  private val keyDeviceId = longPreferencesKey("sync_device_id")
  private val keyPeers = stringPreferencesKey("sync_peers_json")
  private val keyAddresses = stringPreferencesKey("sync_addresses_json")

  /** Writes that have been issued and have not reached disk yet; see [awaitWrites]. */
  private val pendingWrites: MutableSet<Job> =
    java.util.concurrent.ConcurrentHashMap.newKeySet()

  @Volatile private var cached: UserSettings? = null

  val settings: UserSettings
    get() = cached ?: loadSettings().also { cached = it }

  private fun loadSettings(): UserSettings = runBlocking {
    val raw = context.dataStore.data.first()[keySettings]
    if (raw.isNullOrBlank()) UserSettings() else decode(raw)
  }

  private fun decode(raw: String): UserSettings =
    runCatching { json.decodeFromString(UserSettings.serializer(), raw) }.getOrDefault(UserSettings())

  /**
   * Applies [transform] and persists the result.
   *
   * The new value is published to [cached] **before** the write is handed off, so every reader in
   * this process sees it immediately — which is what the whole app depends on (`UiState` is built
   * from `preferences.settings` synchronously).
   *
   * The disk write itself no longer runs on the calling thread. It used to be a `runBlocking {
   * dataStore.edit { … } }`, and the callers are Compose click handlers: every star tap, every
   * theme chip, every danmaku slider blocked the main thread on a file write. The write is now
   * serialised onto a single-threaded IO dispatcher instead.
   *
   * **Serialisation matters.** `update` is read-modify-write over the *whole* settings record, so
   * two overlapping writes could otherwise land out of order and resurrect the older value. One
   * worker thread means the writes are applied in the order the calls were made.
   *
   * @return a job that completes once this write has reached disk. Callers that only need the new
   *   value to be visible (every UI path) ignore it; tests that construct a second
   *   [UserPreferences] over the same DataStore file must `join()` it, because that instance reads
   *   the file rather than this object's cache.
   */
  fun update(transform: (UserSettings) -> UserSettings): Job {
    val next = transform(settings)
    cached = next
    val encoded = json.encodeToString(UserSettings.serializer(), next)
    val job = writeScope.launch {
      context.dataStore.edit { it[keySettings] = encoded }
    }
    // Registered before the caller can observe the job, so `awaitWrites()` cannot miss a write
    // that was just issued. `ConcurrentHashMap` because `update` is called from UI threads while
    // `awaitWrites` runs on a test thread.
    pendingWrites += job
    job.invokeOnCompletion { pendingWrites -= job }
    return job
  }

  /**
   * Completes when every write issued so far has reached disk.
   *
   * The seam that keeps the "survives a restart" tests honest: they build a *second*
   * [UserPreferences] over the same file, so they have to wait for the write rather than for the
   * in-memory cache. Production never calls this — `DataStore` flushes on its own, and the process
   * dying between a tap and the write is not a state this app promises to recover.
   */
  fun awaitWrites() {
    // Looped rather than one pass: joining a job can let a queued one start, and a caller that
    // awaits twice in a row expects both to be on disk.
    while (true) {
      val pending = pendingWrites.toList()
      if (pending.isEmpty()) return
      runBlocking { pending.joinAll() }
    }
  }

  // Publish (not suspend) variants used from Compose callbacks.
  //
  // They discard the [Job] that [update] returns: a UI callback only needs the new value to be
  // visible, and it already is by the time these return. Keeping the return type `Unit` also
  // stops the write handle leaking out of this class.
  fun setThemeMode(mode: String) {
    update { it.copy(themeMode = mode) }
  }

  fun setLastPlatform(platform: Platform) {
    update { it.copy(lastPlatform = platform.id) }
  }

  fun setDanmakuEnabled(enabled: Boolean) {
    update { it.copy(danmakuEnabled = enabled) }
  }

  fun setBlockedKeywords(keywords: List<String>) {
    update { it.copy(blockedKeywords = keywords) }
  }

  /**
   * Records a submitted keyword, newest first.
   *
   * Local only: the search history is not a sync scope. Re-searching an old keyword moves it
   * back to the front instead of adding a duplicate.
   */
  fun addSearchHistory(keyword: String) {
    val trimmed = keyword.trim()
    if (trimmed.isEmpty()) return
    update { current ->
      val next = (listOf(trimmed) + current.searchHistory.filterNot { it == trimmed }).take(MAX_SEARCH_HISTORY)
      current.copy(searchHistory = next)
    }
  }

  fun clearSearchHistory() {
    update { it.copy(searchHistory = emptyList()) }
  }

  fun setPreferredQuality(platform: Platform, quality: Int) {
    update { it.copy(preferredQuality = it.preferredQuality + (platform.id to quality)) }
  }

  // ------------------------------------------------------------------ identity

  /**
   * This installation's stable device id.
   *
   * Cached after the first read. It used to hit DataStore on **every** access — and every follow,
   * unfollow, reorder, star, quality change and sync exchange reads it — so tapping a star cost a
   * blocking `dataStore.data.first()` on the calling thread, which for the UI callbacks in
   * `MainViewModel` is the main thread. The value is written once and never changes afterwards,
   * so caching it removes a file read per tap without changing any observable behaviour.
   *
   * `@Volatile` plus a `synchronized` block rather than a plain field: the sync server answers on
   * its own threads and calls this too, and two threads must not both generate an id and write
   * competing values to disk.
   */
  @Volatile private var cachedDeviceId: Long = 0L

  val deviceId: Long
    get() {
      val known = cachedDeviceId
      if (known != 0L) return known
      return synchronized(this) {
        // Re-read under the lock: another thread may have resolved it while this one waited.
        cachedDeviceId.takeIf { it != 0L } ?: runBlocking {
          val prefs = context.dataStore.data.first()
          val existing = prefs[keyDeviceId] ?: 0L
          val resolved = if (existing != 0L) {
            existing
          } else {
            val generated = generateDeviceId()
            context.dataStore.edit { it[keyDeviceId] = generated }
            generated
          }
          cachedDeviceId = resolved
          resolved
        }
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

  // ---------------------------------------------------------------- peer addresses

  /**
   * Where each paired device was last seen.
   *
   * Stored apart from [trustedPeers] because it is the one part of a pairing that legitimately
   * goes stale: the secret identifies the device, the address only says where it was
   * yesterday. Keeping it in memory alone is what left a restarted app with no way to reach a
   * peer except mDNS — and a router that blocks multicast made that "不在线" forever.
   */
  fun peerAddresses(): List<DiscoveredPeer> = runBlocking {
    val raw = context.dataStore.data.first()[keyAddresses]
    if (raw.isNullOrBlank()) {
      emptyList()
    } else {
      runCatching { json.decodeFromString<List<DiscoveredPeer>>(raw) }.getOrDefault(emptyList())
    }
  }

  fun savePeerAddresses(addresses: List<DiscoveredPeer>) {
    val encoded = json.encodeToString(addresses)
    runBlocking { context.dataStore.edit { it[keyAddresses] = encoded } }
  }

  private companion object {
    /** Enough to be useful in the search field, short enough to stay readable. */
    const val MAX_SEARCH_HISTORY = 20
  }
}
