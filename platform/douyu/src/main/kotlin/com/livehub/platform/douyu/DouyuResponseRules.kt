package com.livehub.platform.douyu

import com.livehub.core.model.Platform
import com.livehub.platform.api.PlatformOfflineException
import com.livehub.platform.api.PlatformProtocolException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Turns a Douyu response body into JSON, translating the answers that are not JSON.
 *
 * Douyu serves an HTML notice instead of JSON when a room has been taken down
 * ("您观看的房间已被关闭"). The directory keeps advertising those rooms for a while, so
 * this is a routine state rather than an error in the app — reporting it as
 * "无法解析的内容 (HTTP 200)" made a closed room look like a protocol failure.
 *
 * Extracted from [DouyuApi] so the rules can be tested without a live connection.
 */
object DouyuResponseRules {

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
  }

  /** Markers Douyu uses on the taken-down notice page. */
  private val closedMarkers = listOf("已被关闭", "房间已关闭", "房间不存在")

  /**
   * Parses [body] as a JSON object.
   *
   * @throws PlatformOfflineException when the body is the taken-down notice or any other
   *   HTML page — the room is not playable, and the message says so in plain Chinese.
   * @throws PlatformProtocolException when the body is neither HTML nor valid JSON.
   */
  fun requireJson(body: String, code: Int, label: String): JsonObject {
    if (body.trimStart().startsWith("<")) {
      val closed = closedMarkers.any { body.contains(it) }
      throw PlatformOfflineException(
        if (closed) {
          "该直播间已关闭，请选择其他直播"
        } else {
          "斗鱼返回了网页而不是数据，请稍后重试"
        },
        Platform.DOUYU,
      )
    }
    return try {
      json.parseToJsonElement(body).jsonObjectOrThrow()
    } catch (error: Exception) {
      throw PlatformProtocolException(
        "$label 返回了无法解析的内容 (HTTP $code)",
        Platform.DOUYU,
        error,
      )
    }
  }

  private fun kotlinx.serialization.json.JsonElement.jsonObjectOrThrow(): JsonObject =
    this as? JsonObject
      ?: throw IllegalArgumentException("response was not a JSON object")
}
