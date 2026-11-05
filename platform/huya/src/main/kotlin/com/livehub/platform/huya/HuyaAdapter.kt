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
  /** Supplies the bundled category table; empty in tests. */
  private val assets: com.livehub.platform.api.AssetReader = com.livehub.platform.api.AssetReader.Empty,
) : LivePlatformAdapter {

  override val platform: Platform = Platform.HUYA

  /**
   * Huya publishes no usable catalogue endpoint, so the shipped table is the source.
   * It carries 4 groups and 424 sub-categories; falling back to the network only when
   * the asset is missing keeps old builds working.
   *
   * Led by a 全部直播 entry: `getLiveList?iGid=0` is what huya.com/l ("全部直播") calls, and
   * it paginates properly — 120 rooms per page over `iTotalPage = 73` pages, page 1 vs 2
   * sharing no room, page 74 empty. It is the one feed on the four platforms that really is
   * the whole site. The name matches that page rather than 全部 so it cannot be mistaken for
   * the 全部 that clears a third level on 斗鱼.
   */
  override suspend fun categories(): List<Category> =
    listOf(Category.aggregate(platform, "全部直播")) +
      HuyaCategories.load(assets).ifEmpty { api.categories() }

  /**
   * One page of rooms.
   *
   * The aggregate id maps to `iGid=0`, which is the "全部直播" feed huya.com/l opens. Every
   * other id is a real `iGid` from the shipped table.
   */
  override suspend fun rooms(categoryId: String, cursor: String?, slug: String?): RoomPage {
    val page = cursor?.toIntOrNull() ?: 1
    val gid = if (categoryId == Category.ALL_ID) ALL_GID else categoryId
    val (rooms, hasMore) = api.rooms(gid = gid, page = page, pageSize = PAGE_SIZE)
    return RoomPage(rooms = rooms, nextCursor = if (hasMore) (page + 1).toString() else null)
  }

  /**
   * Search pages are addressed by the server's `start` offset, not by a page number.
   *
   * Huya returns a different number of documents depending on `start` (20 for the first
   * page, 40 afterwards), so the next offset is whatever the previous response consumed —
   * the API hands that back rather than letting the adapter multiply a page count by a
   * page size that does not exist.
   */
  override suspend fun search(keyword: String, cursor: String?): RoomPage {
    val start = cursor?.toIntOrNull() ?: 0
    val page = api.search(keyword = keyword, start = start)
    return RoomPage(rooms = page.rooms, nextCursor = page.nextStart?.toString())
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

  /**
   * Resolves a room for playback from **one** page load.
   *
   * `api.playback` hands back the page it parsed, so the `Streamer` is rebuilt from the
   * same response that produced the URL. Re-fetching it (as this used to) doubled the
   * wait before the first frame and made a working stream fail whenever the redundant
   * second request did.
   *
   * `preferredQuality` is unused on purpose: Huya publishes a single quality (原画) and the
   * reference resolver does not offer a rate choice either, so there is nothing to honour.
   * The CDN axis is real and goes through `preferredLine`.
   */
  override suspend fun playback(
    roomId: String,
    preferredQuality: Int?,
    preferredLine: String?,
  ): PlaybackSource {
    val resolved = api.playback(roomId, preferredLine)
    return PlaybackSource(
      room = resolved.room.toStreamer(),
      variants = resolved.variants,
      defaultVariant = resolved.variants.firstOrNull(),
      lines = resolved.lines,
      selectedLine = resolved.selectedLine,
    )
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

          /** A clean close must reconnect too: Huya ends idle sockets routinely. */
          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (closedByUs) return
            trySend(DanmakuEvent.Disconnected(reason.ifBlank { "连接已关闭" }, willRetry = true))
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

    /**
     * The `iGid` huya.com/l ("全部直播") asks for.
     *
     * `0` is not a real game id — the shipped table's ids start at 1 — which is exactly why
     * `Category.ALL_ID` cannot be sent verbatim: `iGid=all` answers an empty `vList`, i.e. it
     * looks like a site with nothing live.
     */
    const val ALL_GID = "0"

    private val AYYUID_PATTERNS = listOf(
      Regex("""var\s+TT_PROFILE_INFO\s*=\s*\{[\s\S]*?"lp"\s*:\s*"?(\d+)"""),
      Regex("""\\"lp\\"\s*:\s*\\"?(\d+)\\""""),
      Regex("""\\"ayyuid\\"\s*:\s*\\"?(\d+)\\""""),
      Regex("""\\"yyuid\\"\s*:\s*\\"?(\d+)\\""""),
    )

    private val AYYUID_JSON_PATTERN = Regex(""""(?:ayyuid|yyuid|lp|uid)"\s*:\s*"?(\d+)""")
  }
}
