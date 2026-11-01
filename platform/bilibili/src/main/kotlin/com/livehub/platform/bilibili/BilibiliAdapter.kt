package com.livehub.platform.bilibili

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
import kotlinx.coroutines.Job
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

/** Bilibili live integration. */
class BilibiliAdapter(
  private val http: HttpClient,
  private val webSocketClient: OkHttpClient,
  private val cookieStore: BilibiliCookieStore = BilibiliCookieStore.InMemory,
  private val api: BilibiliApi = BilibiliApi(http, cookieStore),
  /** Supplies the bundled category table; empty in tests. */
  private val assets: com.livehub.platform.api.AssetReader = com.livehub.platform.api.AssetReader.Empty,
) : LivePlatformAdapter {

  override val platform: Platform = Platform.BILIBILI

  /**
   * The shipped table carries 13 groups and 386 sub-categories, each id encoded as
   * `"<parentAreaId>:<areaId>"`.
   */
  override suspend fun categories(): List<Category> =
    BilibiliCategories.load(assets).ifEmpty { api.categories() }

  override suspend fun rooms(categoryId: String, cursor: String?, slug: String?): RoomPage {
    val page = cursor?.toIntOrNull() ?: 1
    val area = BilibiliArea.decode(categoryId)
    val (rooms, hasMore) = api.rooms(area = area, page = page, pageSize = PAGE_SIZE)
    return RoomPage(rooms = rooms, nextCursor = if (hasMore) (page + 1).toString() else null)
  }

  override suspend fun search(keyword: String, cursor: String?): RoomPage {
    val page = cursor?.toIntOrNull() ?: 1
    val (rooms, hasMore) = api.search(keyword = keyword, page = page)
    return RoomPage(rooms = rooms, nextCursor = if (hasMore) (page + 1).toString() else null)
  }

  override suspend fun roomDetail(roomId: String): Streamer? = try {
    val info = api.roomInfo(roomId)
    Streamer(
      platform = platform,
      roomId = roomId,
      streamerName = info.streamerName,
      title = info.title,
      coverUrl = info.coverUrl,
      avatarUrl = info.avatarUrl,
      viewerCount = info.viewerCount,
      isLive = info.isLive,
      updatedAt = System.currentTimeMillis(),
    )
  } catch (error: PlatformException) {
    throw error
  } catch (error: Exception) {
    throw PlatformProtocolException("B站房间信息获取失败: ${error.message}", platform, error)
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

  override suspend fun playback(
    roomId: String,
    preferredQuality: Int?,
    preferredLine: String?,
  ): PlaybackSource {
    val source = api.playback(roomId, preferredQuality, preferredLine)
    val detail = runCatching { roomDetail(roomId) }.getOrNull()
    return if (detail != null) source.copy(room = detail) else source
  }

  override fun danmaku(roomId: String): Flow<DanmakuEvent> = callbackFlow {
    var socket: WebSocket? = null
    var closedByUs = false
    var heartbeatJob: Job? = null
    var endpointIndex = 0

    val info = try {
      api.danmakuInfo(roomId)
    } catch (error: Exception) {
      trySend(
        DanmakuEvent.Failed(
          PlatformProtocolException("B站弹幕连接失败: ${error.message}", platform, error),
        ),
      )
      awaitClose { }
      return@callbackFlow
    }

    fun connect() {
      val endpoint = info.endpoints[endpointIndex % info.endpoints.size]
      val request = Request.Builder()
        .url(endpoint.url)
        .header("User-Agent", BilibiliApi.UA_CHROME122)
        .header("Referer", "https://live.bilibili.com/")
        .header("Origin", "https://live.bilibili.com")
        .build()

      socket = webSocketClient.newWebSocket(
        request,
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(
              BilibiliDanmakuProtocol.authFrame(
                uid = info.uid,
                roomId = info.roomId,
                token = info.token,
              ).toByteString(),
            )
            heartbeatJob?.cancel()
            heartbeatJob = launch {
              while (true) {
                delay(BilibiliDanmakuProtocol.HEARTBEAT_INTERVAL_MILLIS)
                webSocket.send(BilibiliDanmakuProtocol.heartbeatFrame().toByteString())
              }
            }
          }

          override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            BilibiliDanmakuProtocol.decode(bytes.toByteArray()).forEach { packet ->
              when (packet.operation) {
                BilibiliDanmakuProtocol.OP_AUTH_REPLY -> trySend(DanmakuEvent.Connected)
                BilibiliDanmakuProtocol.OP_NOTIFICATION -> {
                  val chat = BilibiliDanmakuProtocol.parseDanmaku(packet) ?: return@forEach
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
              }
            }
          }

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closedByUs) return
            endpointIndex++
            heartbeatJob?.cancel()
            trySend(DanmakuEvent.Disconnected(t.message ?: "连接中断", willRetry = true))
            launch {
              delay(RECONNECT_DELAY_MILLIS.milliseconds)
              if (!closedByUs) connect()
            }
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            heartbeatJob?.cancel()
            if (closedByUs) return
            trySend(DanmakuEvent.Disconnected(reason.ifBlank { "连接已关闭" }, willRetry = false))
          }
        },
      )
    }

    connect()

    awaitClose {
      closedByUs = true
      heartbeatJob?.cancel()
      socket?.close(1000, "client closed")
      socket = null
    }
  }.flowOn(Dispatchers.IO)

  companion object {
    const val PAGE_SIZE = 20
    const val RECONNECT_DELAY_MILLIS = 1_200L
  }
}
