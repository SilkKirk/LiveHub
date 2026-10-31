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
 * * [main] — JSON APIs, HTTP/2 enabled, aggressive connection reuse.
 * * [media] — stream manifests and segments, HTTP/2 disabled because several CDNs
 *   break on h2 upgrade, plus a much larger read timeout.
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

  fun mediaBuilder(): OkHttpClient.Builder =
    OkHttpClient.Builder()
      .followRedirects(true)
      .followSslRedirects(true)
      .retryOnConnectionFailure(true)
      .connectTimeout(DEFAULT_CONNECT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
      .readTimeout(READ_STREAM_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
      .writeTimeout(DEFAULT_WRITE_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
      // Many stream CDNs return 302 to hosts that break on HTTP/2 upgrades.
      .protocols(listOf(okhttp3.Protocol.HTTP_1_1))

  private const val DEFAULT_CONNECT_SECONDS = 15L
  private const val DEFAULT_READ_SECONDS = 20L
  private const val DEFAULT_WRITE_SECONDS = 20L
  private const val READ_STREAM_SECONDS = 60L
}

/**
 * A per-client cookie jar backed by the platform's HTTP APIs.
 *
 * The app never relies on `java.net.CookieManager` because Android and the JVM
 * disagree on its default policy; this explicit implementation keeps behaviour
 * identical in unit tests and on device.
 */
class InMemoryCookieJar : CookieJar {
  private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

  override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
    if (cookies.isEmpty()) return
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
}
