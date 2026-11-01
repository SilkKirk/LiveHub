package com.livehub.platform.douyu

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
import kotlin.time.Duration.Companion.milliseconds
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * Douyu integration.
 *
 * List traffic goes through the two public web endpoints; playback needs the signed
 * `getH5Play` call implemented by [DouyuSigner]; danmaku uses the public
 * `danmuproxy.douyu.com` websocket with the framing implemented by
 * [DouyuDanmakuProtocol].
 */
class DouyuAdapter(
  private val http: HttpClient,
  private val webSocketClient: OkHttpClient,
  /**
   * Evaluates the site signing script. Supplied by the app, which owns the JS engine and
   * the bundled crypto library; null in tests, which exercise the formula fallback.
   */
  scriptSigner: com.livehub.platform.douyu.DouyuScriptSigner? = null,
  private val api: DouyuApi = DouyuApi(http, scriptSigner),
) : LivePlatformAdapter {

  override val platform: Platform = Platform.DOUYU

  override suspend fun categories(): List<Category> = api.categories()

  /**
   * One page of rooms.
   *
   * A category id prefixed with `c3:` is a level-3 sub-category ("三级分类"); anything
   * else is a normal `cate2Id`. Both come from `mixListV1`, which paginates properly, so
   * the cursor is simply the page number.
   */
  override suspend fun rooms(categoryId: String, cursor: String?, slug: String?): RoomPage {
    val page = cursor?.toIntOrNull() ?: 1
    val isSubCategory = categoryId.startsWith(DouyuApi.SUB_CATEGORY_PREFIX)
    val rawId = if (isSubCategory) {
      categoryId.removePrefix(DouyuApi.SUB_CATEGORY_PREFIX)
    } else {
      categoryId
    }

    val result = api.rooms(
      categoryId = rawId,
      page = page,
      limit = PAGE_SIZE,
      level = if (isSubCategory) 3 else 2,
    )
    return RoomPage(
      rooms = result.rooms,
      nextCursor = result.nextOffset?.let { it.toString() },
    )
  }

  /** Sub-categories for [categoryId], shown as a third row of chips on the home screen. */
  override suspend fun subCategories(categoryId: String): List<Category> {
    if (categoryId.startsWith(DouyuApi.SUB_CATEGORY_PREFIX)) return emptyList()
    return runCatching { api.subCategories(categoryId) }.getOrDefault(emptyList())
  }

  override suspend fun search(keyword: String, cursor: String?): RoomPage {
    val page = cursor?.toIntOrNull() ?: 1
    val result = api.search(keyword = keyword, page = page, pageSize = PAGE_SIZE)
    return RoomPage(rooms = result.rooms, nextCursor = result.nextOffset)
  }

  override suspend fun roomDetail(roomId: String): Streamer? = try {
    api.betard(roomId).toStreamer()
  } catch (error: PlatformException) {
    throw error
  } catch (error: Exception) {
    throw PlatformProtocolException("斗鱼房间信息获取失败: ${error.message}", platform, error)
  }

  override suspend fun liveStatus(roomId: String): LiveStatus? {
    val info = api.betard(roomId)
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
  ): PlaybackSource = api.playback(roomId, preferredQuality, preferredLine)

  override fun danmaku(roomId: String): Flow<DanmakuEvent> = callbackFlow {
    val backoff = BackoffPolicy(initialDelay = 1_000.milliseconds, maxDelay = 30_000.milliseconds)
    var socket: WebSocket? = null
    var closedByUs = false

    /**
     * Sends [payload] as a **binary** frame.
     *
     * Douyu's protocol is binary only: every message must carry the little-endian
     * header (declared length twice, type 689, two zero bytes) and a NUL terminator.
     * This used to call `websocket.send(text)`, which OkHttp transmits as an opcode-1
     * text frame; the server ignores those, so `loginreq`/`joingroup` never registered
     * and a room silently produced zero chat messages while `DouyuDanmakuProtocol.encode`
     * sat unused next to it.
     */
    fun sendPayload(target: WebSocket, payload: String) {
      target.send(ByteString.of(*DouyuDanmakuProtocol.encode(payload)))
    }

    fun connect() {
      val request = Request.Builder()
        .url(DANMAKU_URL)
        .header("Sec-WebSocket-Protocol", "binary")
        .header("User-Agent", "Mozilla/5.0")
        .build()

      socket = webSocketClient.newWebSocket(
        request,
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            sendPayload(webSocket, DouyuDanmakuProtocol.loginRequest(roomId))
            sendPayload(webSocket, DouyuDanmakuProtocol.joinGroup(roomId))
            trySend(DanmakuEvent.Connected)
            backoff.reset()
          }

          override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val payload = DouyuDanmakuProtocol.decode(bytes.toByteArray()) ?: return
            val chat = DouyuDanmakuProtocol.parseChat(payload) ?: return
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
            trySend(
              DanmakuEvent.Disconnected(
                reason = t.message ?: "连接中断",
                willRetry = true,
              ),
            )
            launch {
              backoff.sleepNext()
              if (!closedByUs) connect()
            }
          }

          /**
           * A clean close also has to reconnect.
           *
           * Douyu closes idle sockets after a few minutes; treating that as final left the
           * chat panel frozen on its last message with no error and no retry.
           */
          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (closedByUs) return
            trySend(
              DanmakuEvent.Disconnected(
                reason = reason.ifBlank { "连接已关闭" },
                willRetry = true,
              ),
            )
            launch {
              backoff.sleepNext()
              if (!closedByUs) connect()
            }
          }
        },
      )
    }

    connect()

    val heartbeatJob = launch {
      while (true) {
        delay(DouyuDanmakuProtocol.HEARTBEAT_INTERVAL_MILLIS)
        socket?.let { sendPayload(it, DouyuDanmakuProtocol.heartbeat()) }
      }
    }

    awaitClose {
      closedByUs = true
      heartbeatJob.cancel()
      socket?.close(1000, "client closed")
      socket = null
    }
  }.flowOn(Dispatchers.IO)


  companion object {
    const val DANMAKU_URL = "wss://danmuproxy.douyu.com:8506/"
    const val PAGE_SIZE = 30
  }
}
