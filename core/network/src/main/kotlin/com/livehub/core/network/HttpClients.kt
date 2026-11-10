package com.livehub.core.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds the shared [OkHttpClient] instances used for platform traffic.
 *
 * Platform endpoints are extremely picky about TLS fingerprints, HTTP/2 and cookie
 * handling, so the app keeps two clients:
 *
 * * [mainBuilder] — JSON APIs, aggressive connection reuse.
 * * [webSocketBuilder] — the danmaku sockets, which must never time out a read and need
 *   keep-alive pings.
 *
 * There used to be a third, `mediaBuilder`, for stream manifests. It was never instantiated:
 * `LivePlayer` builds its own `DefaultHttpDataSource` because the CDNs need per-platform
 * `Referer` headers and cross-protocol redirects. It has been removed rather than left as a
 * plausible-looking entry point that nothing calls.
 */
object HttpClients {
  val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
  val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
  val HTML_MEDIA_TYPE = "text/html; charset=utf-8".toMediaType()

  /** A browser-like desktop UA; several endpoints reject the OkHttp default. */
  const val UA_DESKTOP: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

  /** A browser-like mobile UA used where desktop markup is not served. */
  const val UA_MOBILE: String =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

  /** A minimal UA accepted by endpoints that only check for a non-empty value. */
  const val UA_SIMPLE: String = "Mozilla/5.0"

  fun mainBuilder(debugLogging: Boolean = false): OkHttpClient.Builder =
    OkHttpClient.Builder()
      .cookieJar(InMemoryCookieJar())
      .followRedirects(true)
      .followSslRedirects(true)
      .retryOnConnectionFailure(true)
      .connectTimeout(DEFAULT_CONNECT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
      .readTimeout(DEFAULT_READ_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
      .writeTimeout(DEFAULT_WRITE_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
      .apply {
        if (debugLogging) {
          addInterceptor(
            okhttp3.logging.HttpLoggingInterceptor().apply {
              level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
            },
          )
        }
      }

  /**
   * Client for the danmaku WebSockets.
   *
   * `readTimeout(0)` because a quiet room can go minutes without a frame and a read timeout
   * would tear down a healthy connection; the ping keeps NAT mappings and the platform's own
   * idle reaper happy instead.
   */
  fun webSocketBuilder(debugLogging: Boolean = false): OkHttpClient.Builder =
    mainBuilder(debugLogging)
      .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
      .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)

  private const val DEFAULT_CONNECT_SECONDS = 15L
  private const val DEFAULT_READ_SECONDS = 20L
  private const val DEFAULT_WRITE_SECONDS = 20L
}

/**
 * A per-client cookie jar backed by the platform's HTTP APIs.
 *
 * The app never relies on `java.net.CookieManager` because Android and the JVM
 * disagree on its default policy; this explicit implementation keeps behaviour
 * identical in unit tests and on device.
 *
 * **Bilibili hosts are deliberately excluded.** OkHttp's `BridgeInterceptor` replaces the
 * `Cookie` header wholesale with whatever the jar returns whenever the jar has any cookie
 * for that host — an explicit header set by the caller does not win. Bilibili hands
 * `LIVE_BUVID` to any response and then treats a request *without* `buvid3` as anonymous,
 * answering `-352`; so as soon as the user had opened one room, the jar held `LIVE_BUVID`,
 * the list request lost its `buvid3` placeholder and every category failed until the app
 * was restarted. Bilibili cookies are already owned explicitly by
 * `BilibiliCookieStore` (persisted, and set header by header), so the jar has no business
 * touching them.
 */
class InMemoryCookieJar : CookieJar {
  private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

  override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
    if (cookies.isEmpty()) return
    if (isManagedExternally(url.host)) return
    val key = url.host
    val existing = store.getOrPut(key) { mutableListOf() }
    synchronized(existing) {
      cookies.forEach { incoming ->
        existing.removeAll { it.name == incoming.name && it.path == incoming.path }
        if (incoming.expiresAt > System.currentTimeMillis()) existing.add(incoming)
      }
    }
  }

  override fun loadForRequest(url: HttpUrl): List<Cookie> {
    // Returning empty here is what lets the caller's own `Cookie` header survive: OkHttp
    // only overwrites the header when this returns a non-empty list.
    if (isManagedExternally(url.host)) return emptyList()
    val key = url.host
    val now = System.currentTimeMillis()
    val existing = store[key] ?: return emptyList()
    synchronized(existing) {
      existing.removeAll { it.expiresAt <= now }
      return existing.filter { it.matches(url) }
    }
  }

  /** Serialises all cookies as a `k=v; k=v` header value for [host]. */
  fun headerFor(host: String): String = store[host]
    ?.joinToString("; ") { "${it.name}=${it.value}" }
    .orEmpty()

  fun clear() = store.clear()

  private fun isManagedExternally(host: String): Boolean =
    BILIBILI_HOSTS.any { host == it || host.endsWith(".$it") }

  companion object {
    /**
     * Hosts whose cookies LiveHub sets explicitly and persists itself. Matched exactly or as
     * a suffix so `api.live.bilibili.com`, `api.bilibili.com` and `live.bilibili.com` are all
     * covered.
     */
    private val BILIBILI_HOSTS = listOf("bilibili.com")
  }
}
