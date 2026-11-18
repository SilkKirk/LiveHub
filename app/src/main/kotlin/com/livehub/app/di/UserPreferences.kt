package com.livehub.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
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

/**
 * User facing settings persisted with DataStore.
 *
 * One field was removed rather than wired up: `autoRefreshFollows` (nothing read it; the refresh
 * worker is scheduled from `backgroundRefresh` instead). Decoding is lenient
 * (`ignoreUnknownKeys`), so an installation that still has it in its stored JSON simply drops it on
 * the next write.
 *
 * `dynamicColor` was removed for the same reason in an earlier round — the setting existed but the
 * theme never received it, so it was a switch with nothing behind it — and is back now that the
 * theme takes it (`LiveHubTheme(dynamicColor = …)`). It defaults to **off**: the brand palette is
 * the app's design, and a wallpaper-derived scheme (Material You) is an opt-in, not the default
 * every existing installation would silently adopt on upgrade.
 */
@Serializable
data class UserSettings(
  val themeMode: String = "system",
  /**
   * Whether the theme follows the system wallpaper (Material You) instead of the brand palette.
   *
   * Android 12 and newer only; older phones store whatever was last chosen and simply keep drawing
   * the brand palette, because the theme checks the SDK before reading this.
   */
  val dynamicColor: Boolean = false,
  val danmakuEnabled: Boolean = true,
  val danmakuOpacity: Float = 1.0f,
  val danmakuFontScale: Float = 1.0f,
  val danmakuAreaFraction: Float = 0.5f,
  /** Separate overlay scale for landscape/full screen; the picture is much wider there. */
  val landscapeDanmakuFontScale: Float = 1.15f,
  val blockedKeywords: List<String> = emptyList(),
  val preferredQuality: Map<String, Int> = emptyMap(),
  val lastPlatform: String = Platform.DOUYU.id,
  /** Keywords the search screen shows, newest first. Local only — never synchronised. */
  val searchHistory: List<String> = emptyList(),
  /**
   * Rooms the user has opened, newest first. Local only — never synchronised.
   *
   * The default is mandatory, not cosmetic: this field is missing from every settings record
   * written before it existed, and `decode` replaces a whole record it cannot read with
   * `UserSettings()`. Without the default, upgrading would therefore reset the theme, the follow
   * records and the login records of every existing installation in one go.
   *
   * A room is stored as the whole [Streamer] rather than as a key because the row is drawn before
   * anything has been fetched — a key alone would leave it with no title and no cover until the
   * room was opened again, which is precisely the moment the row exists for.
   */
  val recentRooms: List<Streamer> = emptyList(),
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
  /**
   * Whether the LAN sync server runs outside the sync screen, and whether opening the app syncs
   * once by itself.
   *
   * On by default, and that default is load-bearing twice: it is the behaviour the app is for
   * (two paired devices keep each other current without being parked on the sync page), and a
   * record written before this field existed decodes to it. The decode falls back to
   * `UserSettings()` for a record it cannot read, so a field without a default would reset every
   * other setting along with it.
   *
   * Off is not "no sync at all": the sync page still opens the server while it is on screen,
   * because pairing a new device needs a listening socket before any peer exists.
   */
  val autoSync: Boolean = true,
  /**
   * Whether the periodic background refresh of the follow list may run at all.
   *
   * On by default: that is exactly what the app did before this switch existed, and defaulting to
   * off would silently remove the feature from every existing installation on upgrade. The
   * default is also what a record written before the field existed decodes to, and the decode
   * falls back to `UserSettings()` for a record it cannot read — so a missing default here would
   * reset every other setting along with it.
   */
  val backgroundRefresh: Boolean = true,
  /**
   * Whether the periodic refresh is restricted to Wi-Fi networks.
   *
   * Off by default because it is a restriction, not a behaviour: "off" is what the app always did
   * (refresh on whatever network is up), so turning it on has to be a deliberate choice.
   */
  val refreshOnWifiOnly: Boolean = false,
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
 * blocks on a file write; see [update]. [update] is also the only writer whose *read-modify-write*
 * is atomic, which is why everything that mutates a field goes through it.
 *
 * [saveTrustedPeers] and [savePeerAddresses] are the exception: they still write on the caller's
 * thread, and **not** every caller is on a background dispatcher. `SyncController` is (its whole
 * exchange runs on `Dispatchers.IO`), but `MainViewModel.unpair` is not — it reaches both through
 * `SyncEngine.unpair` and `SyncController.forgetPeer` from a click handler, so removing a paired
 * device performs a blocking read and two blocking writes on the main thread. That is a known,
 * unfixed cost, recorded in `docs/ITERATIONS.md`; it is not a property of the design, and the
 * earlier version of this paragraph claimed otherwise. Fixing it means making the peer list part
 * of the cached settings record (like every other field) rather than a separate key.
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

  /**
   * Guards the read-modify-write in [update].
   *
   * Not the same job as [writeScope]. That queue only orders the *disk writes*; the value they
   * write is computed from `cached` on the caller's thread, and two callers that compute from the
   * same base lose one of the two changes — from the cache and from the file alike. The lock is
   * what makes "read the record, transform it, publish it" one step.
   *
   * The two are genuinely different concerns and both are needed: this lock makes one update
   * atomic, [writeScope] keeps the writes in the order the lock was released.
   */
  private val updateLock = Any()

  /**
   * The context this store was built with, which is the container's application context.
   *
   * [AppContainer] keeps its own `applicationContext` private and offers no accessor, but
   * `MainViewModel` has to hand a context to `WorkManager` when the user switches the background
   * refresh on or off. Going through the container keeps the context coming from the same place
   * every other dependency gets it; the alternative was a static `LiveHubApplication.instance`
   * holder, and process-wide mutable state is worse — every Robolectric test would inherit
   * whichever application the previous test happened to build.
   */
  internal val appContext: Context get() = context

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
   * The disk write itself does not run on the calling thread. It used to be a `runBlocking {
   * dataStore.edit { … } }`, and the callers are Compose click handlers: every star tap, every
   * theme chip, every danmaku slider blocked the main thread on a file write. The write is
   * serialised onto a single-threaded IO dispatcher instead.
   *
   * **Two separate guarantees, and they need two mechanisms.**
   *
   *  * *Order.* Writes reach the file in the order the calls were made, so an older value cannot
   *    land after a newer one and resurrect itself.
   *  * *Atomicity.* [transform] runs under [updateLock] and sees the result of the previous one, so
   *    it is a real read-modify-write rather than a read and a later write.
   *
   * The second one was missing, and only the first was documented — which is the more dangerous
   * half, because the loss is silent. `update` is called from the UI thread (a star tap, a danmaku
   * slider) **and** from the sync server's own threads (`FollowRecordSet` and
   * `CredentialStore` both persist inside `SyncEngine.applyPayload`). A tap that lands while an
   * exchange is applying a payload made both callers read the same base record, and whichever
   * published second overwrote the other's field: the follow was not saved, or the synced record
   * was not. `update` is a whole-record read-modify-write, so "one field lost" means "one *change*
   * lost", both in memory and on disk.
   *
   * **This lock is not the whole story for the two record sets.** `update` makes one transform
   * atomic, but the *base* those transforms are applied to is the follow/login record list, which
   * those stores read on the caller's thread before `update` is entered. That is why
   * `FollowRecordSet` and `CredentialStore` have their own locks around their read-modify-write —
   * see `FollowRecordSet.lock`.
   *
   * @return a job that completes once this write has reached disk. Callers that only need the new
   *   value to be visible (every UI path) ignore it; tests that construct a second
   *   [UserPreferences] over the same DataStore file must `join()` it, because that instance reads
   *   the file rather than this object's cache.
   */
  fun update(transform: (UserSettings) -> UserSettings): Job = synchronized(updateLock) {
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
    // Enqueued inside the lock, so the queue order is the order the transforms were applied in:
    // the single-threaded executor runs submissions in FIFO order.
    job
  }

  /**
   * Returns this installation's pairing secret, generating and storing one on first use.
   *
   * Extracted from `SyncController` so the rule lives next to the storage it protects, and so it
   * can be tested without a live server. Four call sites reach it — the pairing handler (a server
   * connection thread), transport construction, `pairByCode` and the peer sweep — and they can
   * overlap.
   *
   * **The transform must not clobber.** A plain `it.copy(selfSecret = generated)` was the previous
   * shape, and it is wrong even with an atomic `update`: the *value* is generated before the
   * transform runs, so a loser would still overwrite the winner's key while the winner had already
   * handed its own key to a peer in `PairingOutcome.Accepted`. The peer would then authenticate
   * against a secret this device never stored — a permanent "未配对" with no way back except
   * re-pairing. Keeping whatever is already there, and reading the stored value back rather than
   * returning the local guess, makes every caller agree on one secret.
   */
  internal fun selfSecretOrGenerate(generate: () -> String = ::randomSecret): String {
    val known = settings.selfSecret
    if (known.isNotBlank()) return known
    update { current ->
      if (current.selfSecret.isNotBlank()) current else current.copy(selfSecret = generate())
    }
    // Read back rather than return the generated value: a concurrent caller may have won, and the
    // stored secret is the only one a peer can be holding.
    return settings.selfSecret
  }

  private fun randomSecret(): String = UUID.randomUUID().toString().replace("-", "")

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

  /**
   * Switches the palette between the brand colours and the wallpaper's (Material You).
   *
   * A no-op on Android 11 and older is not needed here: the write is cheap and harmless, and the
   * theme is what refuses to draw a dynamic scheme it cannot build. Keeping the preference honest
   * ("what the user chose") rather than conditional ("what this phone can do") means downgrading
   * the ROM does not silently rewrite the choice.
   */
  fun setDynamicColor(enabled: Boolean) {
    update { it.copy(dynamicColor = enabled) }
  }

  fun setLastPlatform(platform: Platform) {
    update { it.copy(lastPlatform = platform.id) }
  }

  fun setDanmakuEnabled(enabled: Boolean) {
    update { it.copy(danmakuEnabled = enabled) }
  }

  /**
   * Switches the periodic follow refresh on or off.
   *
   * Only the preference is written here: scheduling and cancelling the `WorkManager` job needs a
   * context, which this class hands out as [appContext] precisely because `AppContainer` has no
   * accessor for its own — and deciding to enqueue belongs to whoever owns the setting
   * (`MainViewModel`), not to the store.
   */
  fun setBackgroundRefresh(enabled: Boolean) {
    update { it.copy(backgroundRefresh = enabled) }
  }

  /**
   * Restricts the periodic refresh to Wi-Fi.
   *
   * The worker reads this on every run rather than being rescheduled, so changing it takes effect
   * at the next wake-up and does not restart the 30-minute timer.
   */
  fun setRefreshOnWifiOnly(wifiOnly: Boolean) {
    update { it.copy(refreshOnWifiOnly = wifiOnly) }
  }

  /**
   * Turns the always-on LAN server and the one-sync-per-foreground-entry on or off.
   *
   * The setting is read by `MainViewModel` rather than by `SyncController`, because "may a socket
   * be open right now" also depends on the foreground state, and only the ViewModel knows that.
   */
  fun setAutoSync(enabled: Boolean) {
    update { it.copy(autoSync = enabled) }
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

  /**
   * Records a room the user opened, newest first.
   *
   * Reopening a room moves it back to the front rather than adding a second copy, so the row is a
   * list of *rooms* and not of visits — the same rule [addSearchHistory] applies to keywords.
   *
   * The list is capped because [update] re-encodes the whole settings record on every call and
   * this is the only field that grows with use: without a cap, a long browsing session would make
   * every later settings write (a star tap, a slider drag) re-encode an ever-longer JSON document.
   *
   * A room without an id is refused for the same reason a blank keyword is: it cannot be reopened,
   * so recording it would put a card in the row whose only possible outcome is a failed resolve.
   */
  fun addRecentRoom(streamer: Streamer) {
    if (streamer.roomId.isBlank()) return
    update { current ->
      val next = (listOf(streamer) + current.recentRooms.filterNot { it.key == streamer.key })
        .take(MAX_RECENT_ROOMS)
      current.copy(recentRooms = next)
    }
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

  /**
   * The paired devices, cached after the first read.
   *
   * The same treatment [deviceId] got, and for the same reason: this was a blocking DataStore read
   * on **every** call. `SyncEngine` asks for the list several times per exchange, `peerById` asks on
   * every authenticated request (a server thread, so that was a disk hop per request rather than
   * jank), and `MainViewModel.refreshPeers` asks from the **main** thread on every visit to the sync
   * page and after every pair and unpair — which is where it was user-visible.
   *
   * [saveTrustedPeers] republishes the cache, so within one installation the value is never stale.
   * Two instances over the same file only happen in tests, and each of those reads once on first
   * use, which is what makes "a restarted app still sees its peers" testable.
   */
  @Volatile private var cachedPeers: List<TrustedPeer>? = null

  fun trustedPeers(): List<TrustedPeer> {
    cachedPeers?.let { return it }
    val loaded = runBlocking {
      val raw = context.dataStore.data.first()[keyPeers]
      if (raw.isNullOrBlank()) {
        emptyList()
      } else {
        runCatching { json.decodeFromString<List<TrustedPeer>>(raw) }.getOrDefault(emptyList())
      }
    }
    cachedPeers = loaded
    return loaded
  }

  fun saveTrustedPeers(peers: List<TrustedPeer>) {
    // Copied so a caller that keeps mutating its list cannot reach back into the cache.
    val snapshot = peers.toList()
    cachedPeers = snapshot
    val encoded = json.encodeToString(snapshot)
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
   *
   * Cached like [trustedPeers]: `PeerAddressBook` reads this through its `onChange` callback and
   * `SyncController` asks for it on every probe and sweep, so the uncached version paid a disk read
   * per call.
   */
  @Volatile private var cachedAddresses: List<DiscoveredPeer>? = null

  fun peerAddresses(): List<DiscoveredPeer> {
    cachedAddresses?.let { return it }
    val loaded = runBlocking {
      val raw = context.dataStore.data.first()[keyAddresses]
      if (raw.isNullOrBlank()) {
        emptyList()
      } else {
        runCatching { json.decodeFromString<List<DiscoveredPeer>>(raw) }.getOrDefault(emptyList())
      }
    }
    cachedAddresses = loaded
    return loaded
  }

  fun savePeerAddresses(addresses: List<DiscoveredPeer>) {
    val snapshot = addresses.toList()
    cachedAddresses = snapshot
    val encoded = json.encodeToString(snapshot)
    runBlocking { context.dataStore.edit { it[keyAddresses] = encoded } }
  }

  private companion object {
    /** Enough to be useful in the search field, short enough to stay readable. */
    const val MAX_SEARCH_HISTORY = 20

    /**
     * Twelve rooms: several screenfuls of the 120dp strip that reads this, which is many times
     * over what "the room I had open a moment ago" needs. It also keeps the record at a few kB on
     * disk, which matters because every other settings write re-encodes it.
     */
    const val MAX_RECENT_ROOMS = 12
  }
}
