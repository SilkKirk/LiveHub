package com.livehub.platform.douyin

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
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.milliseconds

/** Douyin live integration. */
class DouyinAdapter(
  private val http: HttpClient,
  private val webSocketClient: OkHttpClient,
  private val api: DouyinApi = DouyinApi(http),
) : LivePlatformAdapter {

  override val platform: Platform = Platform.DOUYIN

  override suspend fun categories(): List<Category> = api.categories()

  override suspend fun rooms(categoryId: String, cursor: String?, slug: String?): RoomPage {
    val page = cursor?.toIntOrNull() ?: 1
    val (rooms, hasMore) = api.rooms(categoryId, page)
    return RoomPage(rooms = rooms, nextCursor = if (hasMore) (page + 1).toString() else null)
  }

  override suspend fun search(keyword: String, cursor: String?): RoomPage {
    val page = cursor?.toIntOrNull() ?: 1
    val (rooms, hasMore) = api.search(keyword, page)
    return RoomPage(rooms = rooms, nextCursor = if (hasMore) (page + 1).toString() else null)
  }

  override suspend fun roomDetail(roomId: String): Streamer? = try {
    api.roomInfo(roomId).toStreamer()
  } catch (error: PlatformException) {
    throw error
  } catch (error: Exception) {
    throw PlatformProtocolException("抖音房间信息获取失败: ${error.message}", platform, error)
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
    val room = roomDetail(roomId) ?: Streamer(
      platform = platform,
      roomId = roomId,
      streamerName = "",
      isLive = true,
    )
    return PlaybackSource(room = room, variants = ordered, defaultVariant = ordered.firstOrNull())
  }

  override fun danmaku(roomId: String): Flow<DanmakuEvent> = callbackFlow {
    val backoff = BackoffPolicy(1_000.milliseconds, 30_000.milliseconds)
    var socket: WebSocket? = null
    var closedByUs = false
    var heartbeatJob: Job? = null
    var ackJob: Job? = null
    var lastExt = ""
    var lastLogId = 0L

    fun connect() {
      val request = Request.Builder()
        .url(DANMAKU_URL)
        .header("User-Agent", DouyinSigner.USER_AGENT)
        .header("Origin", "https://live.douyin.com")
        .header("Referer", "https://live.douyin.com/")
        .build()

      socket = webSocketClient.newWebSocket(
        request,
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            trySend(DanmakuEvent.Connected)
            backoff.reset()
            heartbeatJob?.cancel()
            heartbeatJob = launch {
              while (true) {
                delay(DouyinDanmakuProtocol.HEARTBEAT_INTERVAL_MILLIS)
                webSocket.send(buildHeartbeat(roomId).toByteString())
              }
            }
            ackJob?.cancel()
            ackJob = launch {
              while (true) {
                delay(DouyinDanmakuProtocol.ACK_INTERVAL_MILLIS)
                if (lastLogId != 0L) {
                  webSocket.send(
                    DouyinDanmakuProtocol.ackFrame(lastLogId, lastExt).toByteString(),
                  )
                }
              }
            }
          }

          override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val frame = bytes.toByteArray()
            val logId = DouyinDanmakuProtocol.logId(frame)
            if (logId != 0L) lastLogId = logId
            DouyinDanmakuProtocol.internalExt(frame).takeIf { it.isNotBlank() }?.let { lastExt = it }

            DouyinDanmakuProtocol.decodeFrame(frame).forEach { decoded ->
              val chat = when (decoded.method) {
                DouyinDanmakuProtocol.METHOD_CHAT -> DouyinDanmakuProtocol.parseChat(decoded.body)
                DouyinDanmakuProtocol.METHOD_MEMBER -> DouyinDanmakuProtocol.parseMember(decoded.body)
                else -> null
              } ?: return@forEach

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

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closedByUs) return
            heartbeatJob?.cancel()
            ackJob?.cancel()
            trySend(DanmakuEvent.Disconnected(t.message ?: "连接中断", willRetry = true))
            launch {
              backoff.sleepNext()
              if (!closedByUs) connect()
            }
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            heartbeatJob?.cancel()
            ackJob?.cancel()
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
      ackJob?.cancel()
      socket?.close(1000, "client closed")
      socket = null
    }
  }.flowOn(Dispatchers.IO)

  /**
   * Douyin's heartbeat is a `PushFrame` with `payloadType = "hb"`; the server replies
   * with the same type and expects the client to keep sending them.
   */
  private fun buildHeartbeat(roomId: String): ByteArray {
    val out = ByteArrayOutputStream()
    DouyinProto.writeBytesField(1, "hb".toByteArray(Charsets.UTF_8), out)
    DouyinProto.writeBytesField(2, roomId.toByteArray(Charsets.UTF_8), out)
    return out.toByteArray()
  }

  companion object {
    const val DANMAKU_URL = "wss://webcast5-ws-web-lf.douyin.com/webcast/im/push/v2/"
  }
}
