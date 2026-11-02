package com.livehub.app.di

import android.content.Context
import com.livehub.app.sync.NsdPeerDiscovery
import com.livehub.app.sync.SyncController
import com.livehub.core.data.MapPlatformRegistry
import com.livehub.core.data.PlatformGateway
import com.livehub.core.data.PlatformRegistry
import com.livehub.core.data.ResilientPlatformRepository
import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpClients
import com.livehub.core.network.OkHttpHttpClient
import com.livehub.core.sync.SyncEngine
import com.livehub.platform.api.LivePlatformAdapter
import com.livehub.platform.bilibili.BilibiliAdapter
import com.livehub.platform.bilibili.BilibiliCookieStore
import com.livehub.platform.douyin.DouyinAdapter
import com.livehub.platform.douyu.DouyuAdapter
import com.livehub.platform.huya.HuyaAdapter
import okhttp3.OkHttpClient

/**
 * Hand-written dependency container.
 *
 * The app is small enough that a DI framework would add build time and indirection
 * without benefit; every dependency has exactly one instance created here and injected
 * through constructors. Swapping an implementation in a test is a constructor call.
 */
class AppContainer(
  context: Context,
  private val debugLogging: Boolean = false,
) {
  private val applicationContext: Context = context.applicationContext

  /**
   * Reads `assets/` text. Huya, Douyin and Bilibili publish their catalogue as a static
   * document rather than a live endpoint, so the platform modules take this instead of
   * depending on Android.
   */
  private val assetReader: com.livehub.platform.api.AssetReader =
    com.livehub.platform.api.AssetReader { name ->
      runCatching {
        applicationContext.assets.open(name).bufferedReader().use { it.readText() }
      }.getOrNull()
    }

  // ------------------------------------------------------------------ networking

  val jsonClient: OkHttpClient by lazy {
    HttpClients.mainBuilder(debugLogging).build()
  }

  val mediaClient: OkHttpClient by lazy {
    HttpClients.mediaBuilder().build()
  }

  val webSocketClient: OkHttpClient by lazy {
    HttpClients.mainBuilder(debugLogging)
      .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
      .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
      .build()
  }

  val httpClient: HttpClient by lazy { OkHttpHttpClient(jsonClient) }

  // -------------------------------------------------------------------- platform

  val bilibiliCookieStore: BilibiliCookieStore by lazy {
    DataStoreBilibiliCookies(applicationContext)
  }

  val douyinCookieStore: com.livehub.platform.douyin.DouyinCookieStore by lazy {
    DataStoreDouyinCookies(applicationContext)
  }

  val adapters: List<LivePlatformAdapter> by lazy {
    listOf(
      DouyuAdapter(
        http = httpClient,
        webSocketClient = webSocketClient,
        scriptSigner = com.livehub.app.sync.DouyuRhinoSigner.from(applicationContext),
      ),
      HuyaAdapter(http = httpClient, webSocketClient = webSocketClient, assets = assetReader),
      DouyinAdapter(
        http = httpClient,
        webSocketClient = webSocketClient,
        // Persisted so the negotiated ttwid survives a restart. The shipped literal was a
        // year old, and sending it made the first room-open fail until Douyin handed back a
        // fresh cookie in Set-Cookie.
        cookieStore = douyinCookieStore,
        assets = assetReader,
        // The danmaku handshake needs a token from the site's own webmssdk.js, evaluated by
        // the same Rhino engine Douyu's signer uses. Without it the socket cannot open.
        msdkSigner = com.livehub.app.sync.DouyinRhinoSigner.from(applicationContext),
      ),
      BilibiliAdapter(
        http = httpClient,
        webSocketClient = webSocketClient,
        cookieStore = bilibiliCookieStore,
        assets = assetReader,
      ),
    )
  }

  val bilibiliAuth: com.livehub.platform.bilibili.BilibiliAuth by lazy {
    com.livehub.platform.bilibili.BilibiliAuth(http = httpClient, cookieStore = bilibiliCookieStore)
  }

  val platformRegistry: PlatformRegistry by lazy { MapPlatformRegistry(adapters) }

  val platformGateway: PlatformGateway by lazy { PlatformGateway(platformRegistry) }

  val platformRepository: ResilientPlatformRepository by lazy {
    ResilientPlatformRepository(platformGateway)
  }

  // --------------------------------------------------------------- local storage

  val preferences: UserPreferences by lazy { UserPreferences(applicationContext) }

  val followStore: FollowStore by lazy { FollowStore(preferences) }

  val syncTargets: AppSyncTargets by lazy {
    AppSyncTargets(followStore, preferences)
  }

  val syncEngine: SyncEngine by lazy {
    SyncEngine(
      targets = syncTargets,
      deviceIdProvider = { preferences.deviceId },
      deviceNameProvider = { preferences.deviceName },
      peersProvider = { preferences.trustedPeers() },
      peersWriter = { preferences.saveTrustedPeers(it) },
    )
  }

  /**
   * The port the LAN server is actually bound to.
   *
   * Discovery needs it, and the controller needs discovery, so the port lives here where
   * both can see it without a cycle. It starts at the preferred port and is replaced by
   * the real one once the server binds — which matters because the server falls back to
   * an ephemeral port when 47653 is taken, and advertising the constant anyway pointed
   * every peer at a socket nobody owned ("对方拒绝连接" on a perfectly good network).
   */
  @Volatile private var listeningPort: Int = SyncController.DEFAULT_PORT

  val peerDiscovery: NsdPeerDiscovery by lazy {
    NsdPeerDiscovery(
      context = applicationContext,
      deviceId = preferences.deviceId,
      deviceName = preferences.deviceName,
      portProvider = { listeningPort },
    )
  }

  val syncController: SyncController by lazy {
    SyncController(
      preferences = preferences,
      followStore = followStore,
      engine = syncEngine,
      discovery = peerDiscovery,
      // Lets the container know the real port the moment the server binds, so mDNS
      // advertises a socket that exists.
      onListening = { port -> listeningPort = port },
    )
  }
}
