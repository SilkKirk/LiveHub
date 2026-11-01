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

  val adapters: List<LivePlatformAdapter> by lazy {
    listOf(
      DouyuAdapter(
        http = httpClient,
        webSocketClient = webSocketClient,
        scriptSigner = com.livehub.app.sync.DouyuRhinoSigner.from(applicationContext),
      ),
      HuyaAdapter(http = httpClient, webSocketClient = webSocketClient, assets = assetReader),
      DouyinAdapter(http = httpClient, webSocketClient = webSocketClient, assets = assetReader),
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

  val categoryStore: CategorySubscriptionStore by lazy { CategorySubscriptionStore(preferences) }

  val syncTargets: AppSyncTargets by lazy {
    AppSyncTargets(followStore, preferences, categoryStore)
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

  val peerDiscovery: NsdPeerDiscovery by lazy {
    NsdPeerDiscovery(
      context = applicationContext,
      deviceId = preferences.deviceId,
      deviceName = preferences.deviceName,
      portProvider = { SyncController.DEFAULT_PORT },
    )
  }

  val syncController: SyncController by lazy {
    SyncController(
      preferences = preferences,
      followStore = followStore,
      engine = syncEngine,
      discovery = peerDiscovery,
    )
  }
}
