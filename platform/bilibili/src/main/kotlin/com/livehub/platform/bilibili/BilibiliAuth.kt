package com.livehub.platform.bilibili

import com.livehub.core.model.Platform
import com.livehub.core.network.HttpClient
import com.livehub.platform.api.PlatformProtocolException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bilibili QR-code login.
 *
 * The flow is the same one the web client uses: ask for a QR payload, render its URL,
 * then poll until the app is confirmed. On success every `Set-Cookie` header is folded
 * into the shared [BilibiliCookieStore] so playurl / search / danmaku all use the
 * logged-in session.
 */
class BilibiliAuth(
  private val http: HttpClient,
  private val cookieStore: BilibiliCookieStore,
) {
  /** A QR code the UI must render plus the key used to poll it. */
  data class QrCode(val url: String, val key: String)

  /** One poll result. */
  sealed interface PollResult {
    data object Waiting : PollResult

    data object Scanned : PollResult

    data object Expired : PollResult

    data class Confirmed(val cookieHeader: String) : PollResult

    data class Failed(val message: String) : PollResult
  }

  suspend fun requestQrCode(): QrCode {
    val response = http.get(
      URL_QR_GENERATE,
      headers = mapOf("User-Agent" to UA),
    )
    val root = parse(response.body, "qrcode/generate")
    val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
    if (code != 0) {
      throw PlatformProtocolException(
        "B站二维码生成失败: ${root["message"]?.jsonPrimitive?.contentOrNull.orEmpty()}",
        Platform.BILIBILI,
      )
    }
    val data = root["data"]?.jsonObject
      ?: throw PlatformProtocolException("B站二维码数据为空", Platform.BILIBILI)
    val url = data["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val key = data["qrcode_key"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (url.isBlank() || key.isBlank()) {
      throw PlatformProtocolException("B站二维码生成失败", Platform.BILIBILI)
    }
    return QrCode(url = url, key = key)
  }

  suspend fun poll(key: String): PollResult {
    val response = http.get(
      "$URL_QR_POLL?qrcode_key=$key",
      headers = mapOf("User-Agent" to UA),
    )
    val root = parse(response.body, "qrcode/poll")
    val data = root["data"]?.jsonObject ?: return PollResult.Failed("B站登录状态未知")
    return when (data["code"]?.jsonPrimitive?.intOrNull ?: -1) {
      86101 -> PollResult.Waiting
      86090 -> PollResult.Scanned
      86038 -> PollResult.Expired
      0 -> {
        val cookies = collectCookies(response.headersNamed("Set-Cookie"))
        if (cookies.isNotBlank()) cookieStore.save(cookies)
        PollResult.Confirmed(cookieStore.header())
      }
      else -> PollResult.Failed(
        data["message"]?.jsonPrimitive?.contentOrNull ?: "B站登录失败",
      )
    }
  }

  fun logout() = cookieStore.clear()

  fun isLoggedIn(): Boolean = cookieStore.isLoggedIn()

  /**
   * Parses `Set-Cookie` headers into a single request header.
   * Only the `k=v` prefix matters; attributes such as `Path` and `HttpOnly` are dropped.
   */
  internal fun collectCookies(setCookieHeaders: List<String>): String = setCookieHeaders
    .mapNotNull { header ->
      val pair = header.substringBefore(';').trim()
      if (pair.contains('=') && pair.substringBefore('=').isNotBlank()) pair else null
    }
    .joinToString("; ")

  private fun parse(body: String, label: String): JsonObject = runCatching {
    BilibiliApi.jsonStatic.parseToJsonElement(body).jsonObject
  }.getOrElse { error ->
    throw PlatformProtocolException("B站 $label 返回无法解析的 JSON", Platform.BILIBILI, error)
  }

  companion object {
    const val URL_QR_GENERATE = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
    const val URL_QR_POLL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
    private const val UA = "Mozilla/5.0"
    private val jsonStatic = kotlinx.serialization.json.Json {
      ignoreUnknownKeys = true
      isLenient = true
    }
  }
}
