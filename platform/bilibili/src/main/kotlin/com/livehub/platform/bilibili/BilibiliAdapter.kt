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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
   * The shipped table carries 12 groups and 456 sub-categories, each id encoded as
   * `"<parentAreaId>:<areaId>"`. It is generated from `room/v1/Area/getList`, but that
   * endpoint is only consulted when the asset is missing: the table changes a few times a
   * year while the app opens this on every launch.
   *
   * Led by a 热门 entry, which is `index/getList` (`parent_area_id=0&area_id=0`) — the same
   * list live.bilibili.com/all opens on. `BilibiliArea.decode` already maps any id without a
   * colon to [BilibiliArea.HOT], so the aggregate id needs no special case in [rooms]. Until
   * now that path existed but was unreachable: `0:0` is not one of the 456 shipped
   * partitions, so nothing in the UI could ever produce the id and only the tests called it.
   *
   * 热门 is a single page — `page` and `page_size` are ignored, page 2 repeats 97 of page
   * 1's 110 rooms — so it is offered as 热门 rather than 全部, and it is not a substitute
   * for a whole-site feed, which Bilibili does not expose (`second/getList` with
   * `parent_area_id=0` answers `code = -400`).
   */
  override suspend fun categories(): List<Category> =
    listOf(Category.aggregate(platform, "热门")) +
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
    val info = api.liveStatus(roomId)
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

  /**
   * Live chat for [roomId].
   *
   * Reconnection follows the reference client's shape rather than a fixed retry: one
   * coroutine loops over the endpoint list, and the delay between attempts is exponential
   * (1.2 s doubling to at most 12 s) while nothing has ever connected, then resets to 1.6 s
   * once a session was established. A fixed 1.2 s retry hammered Bilibili whenever the room
   * was risk-controlled or the network was down — exactly the cases where the server wants
   * fewer connections, not more.
   *
   * Endpoints are tried in order within one round, so a single bad host cannot pin the
   * session, and the loop only leaves the endpoint list early when authentication actually
   * succeeded (`OP_AUTH_REPLY`), which is the difference between "connected" and "connected
   * to something that will not push messages".
   */
  override fun danmaku(roomId: String): Flow<DanmakuEvent> = callbackFlow {
    val socketRef = AtomicReference<WebSocket?>(null)

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

    val authPacket = BilibiliDanmakuProtocol.authFrame(
      uid = info.uid,
      roomId = info.roomId,
      token = info.token,
    )
    val heartbeatPacket = BilibiliDanmakuProtocol.heartbeatFrame()

    val job = launch {
      var backoffMs = RECONNECT_DELAY_INITIAL_MILLIS
      while (isActive) {
        var connectedOnce = false
        var authenticatedOnce = false
        var lastFailure: Throwable? = null

        for (endpoint in info.endpoints) {
          if (!isActive) break

          val sessionClosed = CompletableDeferred<Throwable?>()
          val authenticated = AtomicBoolean(false)

          val request = Request.Builder()
            .url(endpoint.url)
            .header("User-Agent", BilibiliApi.UA_CHROME122)
            .header("Referer", "https://live.bilibili.com/")
            .header("Origin", "https://live.bilibili.com")
            .build()

          val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
              socketRef.set(webSocket)
              connectedOnce = true
              webSocket.send(authPacket.toByteString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
              BilibiliDanmakuProtocol.decode(bytes.toByteArray()).forEach { packet ->
                when (packet.operation) {
                  BilibiliDanmakuProtocol.OP_AUTH_REPLY -> {
                    authenticated.set(true)
                    authenticatedOnce = true
                    trySend(DanmakuEvent.Connected)
                  }
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
              sessionClosed.complete(t)
            }

            /**
             * A clean close must reconnect.
             *
             * Bilibili closes a socket that has been idle for a while and expects the client
             * to come back; stopping here left the chat panel permanently dead.
             */
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
              sessionClosed.complete(null)
            }
          }

          val socket = webSocketClient.newWebSocket(request, listener)
          socketRef.set(socket)

          val heartbeatJob = launch {
            while (isActive) {
              delay(BilibiliDanmakuProtocol.HEARTBEAT_INTERVAL_MILLIS)
              val current = socketRef.get() ?: break
              // A newer socket replaced this one, or the send failed: stop beating.
              if (current !== socket) break
              if (!current.send(heartbeatPacket.toByteString())) break
            }
          }

          lastFailure = sessionClosed.await()
          heartbeatJob.cancel()
          runCatching { socket.close(1000, "reconnect") }

          if (!isActive) break

          // Only a session that authenticated is worth reusing; otherwise rotate.
          if (authenticated.get()) break
        }

        if (!isActive) break

        // One report per round rather than per endpoint: the UI only needs to know the room
        // is offline and being retried, not which of the six hosts failed last.
        if (!authenticatedOnce) {
          trySend(
            DanmakuEvent.Disconnected(
              lastFailure?.message ?: "连接已关闭",
              willRetry = true,
            ),
          )
        }

        // Grow the delay only while we have never managed to connect; a working session that
        // dropped should come back quickly.
        backoffMs = if (!connectedOnce) {
          (backoffMs * 2).coerceAtMost(RECONNECT_DELAY_MAX_MILLIS)
        } else {
          RECONNECT_DELAY_AFTER_SUCCESS_MILLIS
        }
        delay(backoffMs)
      }
    }

    awaitClose {
      socketRef.getAndSet(null)?.close(1000, "client closed")
      job.cancel()
    }
  }.flowOn(Dispatchers.IO)

  companion object {
    const val PAGE_SIZE = 20

    /** First retry delay; doubles per failed round until it reaches the cap. */
    const val RECONNECT_DELAY_INITIAL_MILLIS = 1_200L

    /** Ceiling for the exponential backoff, so a dead room is not retried forever at speed. */
    const val RECONNECT_DELAY_MAX_MILLIS = 12_000L

    /** Delay after a session that had worked dropped: reconnect promptly. */
    const val RECONNECT_DELAY_AFTER_SUCCESS_MILLIS = 1_600L
  }
}
