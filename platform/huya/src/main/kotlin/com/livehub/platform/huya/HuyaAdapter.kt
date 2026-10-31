package com.livehub.platform.huya

import com.livehub.core.common.BackoffPolicy
import com.livehub.core.model.Category
import com.livehub.core.model.DanmakuMessage
import com.livehub.core.model.LiveStatus
import com.livehub.core.model.Platform
import com.livehub.core.model.PlaybackSource
import com.livehub.core.model.RoomPage
import com.livehub.core.model.Streamer
import com.livehub.core.network.HttpClient
import com.livehub.platform.api.DanmakuEvent
import com.livehub.platform.api.LivePlatformAdapter
import com.livehub.platform.api.PlatformException
import com.livehub.platform.api.PlatformProtocolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.time.Duration.Companion.milliseconds

/** Huya integration: list, search, HTML-scraped playback and Tars danmaku. */
class HuyaAdapter(
  private val http: HttpClient,
  private val webSocketClient: OkHttpClient,
  private val api: HuyaApi = HuyaApi(http),
) : LivePlatformAdapter {

  override val platform: Platform = Platform.HUYA

  override suspend fun categories(): List<Category> = api.categories()

  override suspend fun rooms(categoryId: String, cursor: String?): RoomPage {
    val page = cursor?.toIntOrNull() ?: 1
    val (rooms, hasMore) = api.rooms(gid = categoryId, page = page, pageSize = PAGE_SIZE)
    return RoomPage(rooms = rooms, nextCursor = if (hasMore) (page + 1).toString() else null)
  }

  override suspend fun search(keyword: String, cursor: String?): RoomPage {
    val page = cursor?.toIntOrNull() ?: 1
    val (rooms, hasMore) = api.search(keyword = keyword, page = page)
    return RoomPage(rooms = rooms, nextCursor = if (hasMore) (page + 1).toString() else null)
  }

  override suspend fun roomDetail(roomId: String): Streamer? = try {
    api.roomInfo(roomId).toStreamer()
  } catch (error: PlatformException) {
    throw error
  } catch (error: Exception) {
    throw PlatformProtocolException("虎牙房间信息获取失败: ${error.message}", platform, error)
  }

  override suspend fun liveStatus(roomId: String): LiveStatus? {
    val info = api.roomInfo(roomId)
    return LiveStatus(
      platform = platform,
      roomId = roomId,
      isLive = info.isLive,
      title = info.title,
      viewerCount = info.viewerCount,
      coverUrl = info.coverUrl,
      checkedAt = System.currentTimeMillis(),
    )
  }

  override suspend fun playback(roomId: String, preferredQuality: Int?): PlaybackSource {
    val variants = api.playback(roomId)
    val ordered = preferredQuality
      ?.let { wanted -> variants.sortedBy { if (it.qualityCode == wanted) 0 else 1 } }
      ?: variants
    val room = api.roomInfo(roomId).toStreamer()
    return PlaybackSource(room = room, variants = ordered, defaultVariant = ordered.firstOrNull())
  }

  override fun danmaku(roomId: String): Flow<DanmakuEvent> = callbackFlow {
    val backoff = BackoffPolicy(1_000.milliseconds, 30_000.milliseconds)
    var socket: WebSocket? = null
    var closedByUs = false
    var lastFrameAt = 0L

    val ayyuid = runCatching { resolveAyyuid(roomId) }.getOrDefault(roomId)

    fun connect() {
      val request = Request.Builder()
        .url(HuyaDanmakuProtocol.WS_URL)
        .header("Origin", "https://www.huya.com")
        .header("Referer", "https://www.huya.com/")
        .header("User-Agent", HuyaDanmakuProtocol.WS_UA)
        .build()

      socket = webSocketClient.newWebSocket(
        request,
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(HuyaDanmakuProtocol.registerFrame(ayyuid).toByteString())
            webSocket.send(Tars.heartbeatFrame().toByteString())
            lastFrameAt = System.currentTimeMillis()
            trySend(DanmakuEvent.Connected)
            backoff.reset()
          }

          override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            lastFrameAt = System.currentTimeMillis()
            val raw = bytes.toByteArray()
            val chat = runCatching { HuyaDanmakuProtocol.decodeChat(raw) }.getOrNull() ?: return
            trySend(
              DanmakuEvent.Message(
                DanmakuMessage(
                  roomKey = Streamer.keyOf(platform, roomId),
                  userName = chat.first,
                  text = chat.second,
                  receivedAt = System.currentTimeMillis(),
                ),
              ),
            )
          }

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closedByUs) return
            trySend(DanmakuEvent.Disconnected(t.message ?: "连接中断", willRetry = true))
            launch {
              backoff.sleepNext()
              if (!closedByUs) connect()
            }
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (closedByUs) return
            trySend(DanmakuEvent.Disconnected(reason.ifBlank { "连接已关闭" }, willRetry = false))
          }
        },
      )
    }

    connect()

    val heartbeatJob = launch {
      while (true) {
        delay(HuyaDanmakuProtocol.HEARTBEAT_INTERVAL_MILLIS)
        val idle = System.currentTimeMillis() - lastFrameAt
        if (idle > HuyaDanmakuProtocol.IDLE_RECONNECT_MILLIS) {
          socket?.cancel()
          continue
        }
        if (idle > HuyaDanmakuProtocol.IDLE_RESUBSCRIBE_MILLIS) {
          socket?.send(HuyaDanmakuProtocol.registerFrame(ayyuid).toByteString())
        }
        socket?.send(Tars.heartbeatFrame().toByteString())
      }
    }

    awaitClose {
      closedByUs = true
      heartbeatJob.cancel()
      socket?.close(1000, "client closed")
      socket = null
    }
  }.flowOn(Dispatchers.IO)

  /**
   * Huya danmaku requires the broadcaster's `ayyuid`, which is embedded in the room
   * page. When it cannot be found the room id is an acceptable fallback: chat still
   * works for most rooms, only some gifts are filtered out.
   */
  private suspend fun resolveAyyuid(roomId: String): String {
    val html = runCatching {
      http.get(
        "$roomUrl/$roomId",
        headers = mapOf(
          "User-Agent" to HuyaDanmakuProtocol.WS_UA,
          "Referer" to "https://www.huya.com/",
        ),
      ).requireSuccess().body
    }.getOrNull().orEmpty()

    if (html.isNotBlank()) {
      AYYUID_PATTERNS.forEach { regex ->
        regex.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
      }
    }

    val cache = runCatching {
      http.get(
        "https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid=$roomId",
        headers = mapOf("User-Agent" to HuyaDanmakuProtocol.WS_UA),
      ).requireSuccess().body
    }.getOrNull().orEmpty()
    if (cache.isNotBlank()) {
      AYYUID_JSON_PATTERN.find(cache)?.groupValues?.getOrNull(1)?.let { return it }
    }
    return roomId
  }

  private val roomUrl: String get() = HuyaApi.URL_ROOM

  companion object {
    const val PAGE_SIZE = 30

    private val AYYUID_PATTERNS = listOf(
      Regex("""var\s+TT_PROFILE_INFO\s*=\s*\{[\s\S]*?"lp"\s*:\s*"?(\d+)"""),
      Regex("""\\"lp\\"\s*:\s*\\"?(\d+)\\""""),
      Regex("""\\"ayyuid\\"\s*:\s*\\"?(\d+)\\""""),
      Regex("""\\"yyuid\\"\s*:\s*\\"?(\d+)\\""""),
    )

    private val AYYUID_JSON_PATTERN = Regex(""""(?:ayyuid|yyuid|lp|uid)"\s*:\s*"?(\d+)""")
  }
}
