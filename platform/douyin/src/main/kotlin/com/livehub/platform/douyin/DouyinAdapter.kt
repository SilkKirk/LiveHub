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
import kotlin.time.Duration.Companion.milliseconds

/** Douyin live integration. */
class DouyinAdapter(
  private val http: HttpClient,
  private val webSocketClient: OkHttpClient,
  private val api: DouyinApi = DouyinApi(http),
  /** Supplies the bundled category table; empty in tests. */
  private val assets: com.livehub.platform.api.AssetReader = com.livehub.platform.api.AssetReader.Empty,
  /**
   * Signs the danmaku handshake. Supplied by the app, which owns the JS engine and the
   * bundled `webmssdk.js`; null in tests, where the socket is never opened.
   */
  private val msdkSigner: DouyinMsdkSigner? = null,
) : LivePlatformAdapter {

  override val platform: Platform = Platform.DOUYIN

  /**
   * The shipped table carries 9 groups and 306 partitions; each id encodes
   * `"<partitionType>:<partition>"`.
   */
  override suspend fun categories(): List<Category> =
    DouyinCategoryCatalog.load(assets).ifEmpty { api.categories() }

  /**
   * One page of rooms for [categoryId].
   *
   * The partition endpoint is preferred because it is the only one that can filter. It is
   * also the one Douyin currently refuses: verified live, it answers HTTP 200 with a
   * zero-length body for every partition. When it yields nothing — which is the normal case
   * today, not an error — the anonymous recommendation feed is used instead. The feed
   * ignores the partition but returns real rooms, so the tab stays useful rather than
   * permanently empty; the trade-off is visible in the UI, which labels it 推荐.
   */
  override suspend fun rooms(categoryId: String, cursor: String?, slug: String?): RoomPage {
    val page = cursor?.toIntOrNull() ?: 1
    // Ids come from the shipped catalogue encoded as "<partitionType>:<partition>";
    // a bare number is still accepted for older callers.
    val ids = DouyinCategoryCatalog.idsOf(categoryId)
    // A bare id keeps working for callers that predate the catalogue.
    val partitionType = ids?.first ?: "1"
    val partition = ids?.second ?: categoryId

    val partitioned = runCatching {
      api.rooms(partition = partition, partitionType = partitionType, page = page)
    }.getOrNull()
    if (partitioned != null && partitioned.first.isNotEmpty()) {
      return RoomPage(
        rooms = partitioned.first,
        nextCursor = if (partitioned.second) (page + 1).toString() else null,
      )
    }

    // Fallback: the feed only serves one page, so no cursor is offered.
    val feed = runCatching { api.feed(offset = (page - 1) * DouyinApi.PAGE_SIZE, count = DouyinApi.PAGE_SIZE) }
      .getOrDefault(emptyList<Streamer>() to false)
    return RoomPage(rooms = feed.first, nextCursor = null)
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

  override suspend fun playback(
    roomId: String,
    preferredQuality: Int?,
    preferredLine: String?,
  ): PlaybackSource {
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

  /**
   * Opens the danmaku socket.
   *
   * The handshake needs a real URL: Douyin rejects a bare `webcast/im/push/v2/` connection.
   * Each attempt therefore resolves the room's numeric id and `user_unique_id` from the room
   * page, builds the ~30 parameter query, and asks [msdkSigner] for the `signature` the
   * server checks. A blank signature means the JS engine is missing (JVM tests) or the
   * bundle failed, in which case the flow reports a readable failure instead of silently
   * retrying a socket that cannot ever open.
   */
  override fun danmaku(roomId: String): Flow<DanmakuEvent> = callbackFlow {
    val backoff = BackoffPolicy(1_000.milliseconds, 30_000.milliseconds)
    var socket: WebSocket? = null
    var closedByUs = false
    var heartbeatJob: Job? = null
    var lastExt = ""
    var baseIndex = 0

    suspend fun resolveInit(): DouyinDanmakuHandshake.RoomInit? {
      val html = runCatching { api.roomPageHtml(roomId) }.getOrNull().orEmpty()
      val userUniqueId = DouyinDanmakuHandshake.extractUserUniqueId(html)
        .ifBlank { DouyinDanmakuHandshake.randomUserUniqueId() }
      val numericRoomId = DouyinDanmakuHandshake.extractRoomId(html)
        .ifBlank { runCatching { api.roomInfo(roomId).roomId }.getOrNull().orEmpty() }
        .ifBlank { roomId }
      if (numericRoomId.isBlank()) return null
      return DouyinDanmakuHandshake.RoomInit(
        roomId = numericRoomId,
        userUniqueId = userUniqueId,
        cookieHeader = DouyinSigner.DEFAULT_COOKIE,
      )
    }

    /**
     * One connection attempt.
     *
     * @return true when the socket opened; the caller then waits on [sessionEnded] before
     *   trying again, so a live socket is never duplicated.
     */
    suspend fun connectOnce(sessionEnded: kotlinx.coroutines.CompletableDeferred<Unit>): Boolean {
      val init = resolveInit() ?: return false
      val signature = msdkSigner?.signature(
        roomId = init.roomId,
        userUniqueId = init.userUniqueId,
        webcastSdkVersion = DouyinDanmakuHandshake.WEBCAST_SDK_VERSION,
        userAgent = DouyinSigner.USER_AGENT,
      )
      val base = DouyinDanmakuHandshake.WS_BASES[baseIndex % DouyinDanmakuHandshake.WS_BASES.size]
      baseIndex++
      val url = DouyinDanmakuHandshake.buildUrl(base, init, signature) ?: return false

      val request = Request.Builder()
        .url(url)
        .header("User-Agent", DouyinSigner.USER_AGENT)
        .header("Origin", "https://live.douyin.com")
        .header("Referer", "https://live.douyin.com/")
        .header("Cookie", init.cookieHeader)
        .build()

      val opened = java.util.concurrent.CountDownLatch(1)
      // Written from OkHttp's reader thread, read from this coroutine, so it needs the
      // latch's happens-before rather than a plain local.
      val openedOk = java.util.concurrent.atomic.AtomicBoolean(false)
      socket = webSocketClient.newWebSocket(
        request,
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            openedOk.set(true)
            opened.countDown()
            trySend(DanmakuEvent.Connected)
            backoff.reset()
            heartbeatJob?.cancel()
            heartbeatJob = launch {
              while (true) {
                delay(DouyinDanmakuHandshake.HEARTBEAT_INTERVAL_MILLIS)
                webSocket.send(buildHeartbeat().toByteString())
              }
            }
          }

          override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val frame = bytes.toByteArray()
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

            // Ack only when the frame asks for it, keyed to that frame's log id. Acking
            // every frame on a timer — the previous behaviour — is not accepted: the server
            // waits for the ack of the specific frame and drops the socket otherwise.
            if (DouyinDanmakuProtocol.needsAck(frame)) {
              val logId = DouyinDanmakuProtocol.logId(frame)
              if (logId != 0L) {
                webSocket.send(DouyinDanmakuProtocol.ackFrame(logId, lastExt).toByteString())
              }
            }
          }

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            opened.countDown()
            heartbeatJob?.cancel()
            if (!closedByUs) {
              trySend(DanmakuEvent.Disconnected(t.message ?: "连接中断", willRetry = true))
            }
            sessionEnded.complete(Unit)
          }

          /** A clean close must reconnect: Douyin rotates sockets server-side regularly. */
          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            opened.countDown()
            heartbeatJob?.cancel()
            if (!closedByUs) {
              trySend(DanmakuEvent.Disconnected(reason.ifBlank { "连接已关闭" }, willRetry = true))
            }
            sessionEnded.complete(Unit)
          }
        },
      )

      // Wait for the handshake so a rejected signature is reported here rather than being
      // mistaken for a healthy connection that never delivers a message.
      runCatching {
        opened.await(OPEN_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
      }
      return openedOk.get()
    }

    val connectJob = launch {
      while (!closedByUs) {
        val sessionEnded = kotlinx.coroutines.CompletableDeferred<Unit>()
        val opened = runCatching { connectOnce(sessionEnded) }.getOrDefault(false)
        if (!opened) {
          trySend(
            DanmakuEvent.Disconnected(
              reason = if (msdkSigner == null) {
                "抖音弹幕签名不可用（缺少 JS 引擎）"
              } else {
                "抖音弹幕连接失败"
              },
              willRetry = true,
            ),
          )
          socket?.cancel()
          socket = null
          backoff.sleepNext()
          continue
        }
        // Block until this session dies, then back off before the next attempt.
        sessionEnded.await()
        socket = null
        if (!closedByUs) backoff.sleepNext()
      }
    }

    awaitClose {
      closedByUs = true
      connectJob.cancel()
      heartbeatJob?.cancel()
      socket?.close(1000, "client closed")
      socket = null
    }
  }.flowOn(Dispatchers.IO)

  /**
   * Douyin's heartbeat reply, built by the protocol object.
   *
   * The old local builder wrote `"hb"` to field 1 and the room id to field 2. Neither is a
   * `PushFrame` field that exists: `payloadType` is field 7 and the payload is field 8, so
   * the server saw a frame with no recognizable type.
   */
  private fun buildHeartbeat(): ByteArray = DouyinDanmakuProtocol.heartbeatFrame()

  companion object {
    /**
     * How long a handshake may take before it is treated as rejected.
     *
     * Douyin accepts the socket only after validating `signature`, so a wrong token shows up
     * as a handshake that never completes rather than an HTTP error.
     */
    private const val OPEN_TIMEOUT_MILLIS = 12_000L
  }
}
