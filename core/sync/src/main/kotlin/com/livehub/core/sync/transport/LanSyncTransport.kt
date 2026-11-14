package com.livehub.core.sync.transport

import com.livehub.core.sync.DiscoveredPeer
import com.livehub.core.sync.SyncEnvelope
import com.livehub.core.sync.SyncCodec
import com.livehub.core.sync.SyncHttpTransport
import com.livehub.core.sync.SyncPayload
import com.livehub.core.sync.SyncRecord
import com.livehub.core.sync.SyncScope
import com.livehub.core.sync.SyncTransport
import com.livehub.core.sync.SyncTransportException
import com.livehub.core.sync.SyncFailureReason
import com.livehub.core.sync.TrustedPeer
import kotlinx.serialization.json.JsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * [SyncTransport] implementation that talks to [com.livehub.core.sync.server.SyncHttpServer]
 * over the LAN.
 *
 * Uses a raw socket rather than OkHttp because the exchange is a handful of tiny JSON
 * requests and the server speaks a deliberately minimal HTTP dialect; a dedicated
 * client keeps both sides symmetrical and easy to reason about.
 */
class LanSyncTransport(
  private val deviceId: Long,
  private val deviceName: String,
  private val localSecret: String,
  private val peersProvider: () -> List<TrustedPeer>,
  private val addressProvider: (TrustedPeer) -> DiscoveredPeer?,
  private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
  /**
   * Budget for the TCP handshake alone.
   *
   * Kept apart from [timeoutMillis] because the two failures look nothing alike. A peer that
   * answered yesterday but whose lease moved, or one that went to sleep, leaves the connect
   * hanging until the socket timeout — previously the full read budget, so the sync button
   * sat there for eight seconds per request before reporting "部分同步失败". A live device
   * completes the handshake in milliseconds, so a short connect budget turns that case into
   * a prompt, actionable failure and leaves the long budget for actually reading a payload.
   */
  private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
  /** Port this device listens on; advertised so peers can call back. */
  private val localPort: Int = 0,
) : SyncTransport {

  /**
   * Fetches what [peer] knows for [scope], paging through the parts.
   *
   * The server returns one part per request because a scope can exceed a single response.
   * The parts are concatenated here so the rest of the system keeps seeing one payload per
   * scope — only this method and the server know the transfer is piecewise.
   *
   * **Consistency note.** Each part is built from a fresh snapshot, so a record inserted or
   * removed *between* two part requests can shift where the next part starts. A shifted
   * record may then be delivered twice (harmless — the merge is idempotent per key) or
   * missed. A miss is repaired by the next sync, and this only applies to scopes past
   * [SyncPayload.MAX_RECORDS_PER_PAYLOAD], which in practice means a follow list of several
   * hundred rooms being edited during a transfer that takes milliseconds. Making it strictly
   * atomic would need a snapshot token on the wire, which is not worth the protocol surface
   * for a case this narrow.
   */
  override suspend fun pull(peer: TrustedPeer, scope: SyncScope): SyncPayload? {
    val address = addressProvider(peer) ?: throw SyncTransportException(
      SyncFailureReason.REFUSED,
      "${peer.deviceName} 不在线",
    )

    var allRecords = mutableListOf<SyncRecord<JsonObject>>()
    var lastPayload: SyncPayload? = null
    var chunkIndex = 0
    while (true) {
      val response = call(
        peer = peer,
        host = address.host,
        port = address.port,
        path = "${SyncEnvelope.PATH_PULL}?scope=${scope.wireName}&chunk=$chunkIndex",
        envelope = SyncEnvelope(
          deviceId = deviceId,
          deviceName = deviceName,
          // Present our own secret: the peer stored exactly this during pairing.
          secret = localSecret,
          preferredPort = localPort,
        ),
      )
      response.error?.let { throw SyncTransportException(SyncFailureReason.PROTOCOL, it) }
      val part = response.payload ?: return null

      lastPayload = part
      allRecords += SyncCodec.decodeRecords(part.entries)
      if (!part.hasMoreChunks) break
      chunkIndex += 1
      // Guard against a peer that keeps reporting more parts than it can possibly have.
      if (chunkIndex > MAX_PULL_CHUNKS) {
        throw SyncTransportException(SyncFailureReason.PROTOCOL, "同步分片数量异常")
      }
    }

    val base = lastPayload ?: return null
    // The caller merges on `(hlc, deviceId)`, so the concatenation is order independent and
    // the sequence number only has to be uniform across the parts of one pull.
    val merged = base.copy(
      entries = SyncCodec.encodeRecords(allRecords),
      chunkIndex = 0,
      chunkCount = 1,
    )
    return merged
  }

  override suspend fun push(peer: TrustedPeer, payload: SyncPayload): SyncPayload? {
    val address = addressProvider(peer) ?: throw SyncTransportException(
      SyncFailureReason.REFUSED,
      "${peer.deviceName} 不在线",
    )
    val envelope = SyncEnvelope(
      deviceId = deviceId,
      deviceName = deviceName,
      secret = localSecret,
      preferredPort = localPort,
      payload = payload,
    )
    val response = call(
      peer = peer,
      host = address.host,
      port = address.port,
      path = SyncEnvelope.PATH_PUSH,
      envelope = envelope,
    )
    response.error?.let { throw SyncTransportException(SyncFailureReason.PROTOCOL, it) }
    return response.payload
  }

  override suspend fun probe(peer: TrustedPeer): Boolean {
    val address = addressProvider(peer) ?: return false
    return runCatching {
      val response = call(
        peer = peer,
        host = address.host,
        port = address.port,
        path = SyncEnvelope.PATH_PING,
        envelope = SyncEnvelope(
          deviceId = deviceId,
          deviceName = deviceName,
          secret = localSecret,
          preferredPort = localPort,
        ),
      )
      response.error == null && response.serverDeviceId == peer.deviceId
    }.getOrDefault(false)
  }

  /** One HTTP round trip; exposed for the pairing flow too. */
  fun call(
    peer: TrustedPeer,
    host: String,
    port: Int,
    path: String,
    envelope: SyncEnvelope,
    timeoutMillis: Int? = null,
  ): SyncEnvelope {
    val budget = timeoutMillis ?: this.timeoutMillis
    val body = SyncEnvelope.encode(envelope)
    val response = try {
      Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), minOf(budget, connectTimeoutMillis))
        socket.soTimeout = budget

        val output: OutputStream = BufferedOutputStream(socket.getOutputStream())
        val request = buildString {
          append("POST ").append(path).append(" HTTP/1.1\r\n")
          append("Host: ").append(host).append(':').append(port).append("\r\n")
          append("Content-Type: application/json; charset=utf-8\r\n")
          append("Content-Length: ").append(body.toByteArray(Charsets.UTF_8).size).append("\r\n")
          append("Connection: close\r\n\r\n")
          append(body)
        }
        output.write(request.toByteArray(Charsets.UTF_8))
        output.flush()

        val input: InputStream = BufferedInputStream(socket.getInputStream())
        readResponse(input)
      }
    } catch (error: SocketTimeoutException) {
      throw SyncTransportException(SyncFailureReason.TIMEOUT, "连接超时", error)
    } catch (error: UnknownHostException) {
      throw SyncTransportException(SyncFailureReason.REFUSED, "无法解析地址", error)
    } catch (error: java.net.ConnectException) {
      throw SyncTransportException(SyncFailureReason.REFUSED, "对方拒绝连接", error)
    } catch (error: Exception) {
      // `message` is null on exceptions that carry no text, `NetworkOnMainThreadException`
      // being the one that actually bit us: the whole pairing flow reported a bare
      // "同步失败" because the socket had been opened on the main thread. Fall back to the
      // exception name so the next such failure names itself instead of hiding.
      throw SyncTransportException(
        SyncFailureReason.UNKNOWN,
        error.message?.takeIf { it.isNotBlank() } ?: (error::class.java.simpleName ?: "同步失败"),
        error,
      )
    }

    if (response.status == 403) {
      // The server puts the reason in the envelope body — "配对码已过期", "配对码不正确",
      // "未配对" … — and that is the text the user needs. Falling back to a fixed string
      // hid the difference between an expired code and a revoked pairing.
      val stated = runCatching { SyncEnvelope.decode(response.body).error }
        .getOrNull()?.takeIf { it.isNotBlank() }
      throw SyncTransportException(
        SyncFailureReason.UNAUTHORIZED,
        stated ?: "配对已失效，请重新配对",
      )
    }
    if (response.status !in 200..299) {
      throw SyncTransportException(SyncFailureReason.PROTOCOL, "对方返回 HTTP ${response.status}")
    }
    val decoded = runCatching { SyncEnvelope.decode(response.body) }
      .getOrElse { throw SyncTransportException(SyncFailureReason.PROTOCOL, "响应无法解析") }
    if (decoded.protocolVersion != SyncEnvelope.PROTOCOL_VERSION) {
      throw SyncTransportException(
        SyncFailureReason.VERSION_MISMATCH,
        "设备协议版本不一致 (${decoded.protocolVersion})",
      )
    }
    // A shared scope the peer has switched off. This is answered with a 2xx because nothing
    // failed, so it has to be recognised by its code here — the response never reaches the
    // callers' generic "the server said something" handling.
    if (decoded.errorCode == SyncEnvelope.ERROR_SCOPE_DISABLED) {
      throw SyncTransportException(SyncFailureReason.SCOPE_DISABLED, "对方未开启该同步范围")
    }
    return decoded
  }

  private fun readResponse(input: InputStream): RawResponse {
    val statusLine = readLine(input) ?: return RawResponse(0, "")
    val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0

    var contentLength = -1
    while (true) {
      val line = readLine(input) ?: break
      if (line.isEmpty()) break
      if (line.startsWith("Content-Length:", ignoreCase = true)) {
        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: -1
      }
    }

    val body = if (contentLength >= 0) {
      // A peer we are pairing with is still an unverified address, so the declared length is not
      // trusted: `ByteArray(contentLength)` would let any listener on a swept address answer with a
      // huge `Content-Length` and take the phone down. The request budget is the only other guard,
      // and it bounds time, not memory.
      if (contentLength > MAX_RESPONSE_BYTES) {
        throw SyncTransportException(SyncFailureReason.PROTOCOL, "响应过大")
      }
      val buffer = ByteArray(contentLength)
      var total = 0
      while (total < contentLength) {
        val read = input.read(buffer, total, contentLength - total)
        if (read <= 0) break
        total += read
      }
      String(buffer, 0, total, Charsets.UTF_8)
    } else {
      // No `Content-Length`: the server always sends one, so this is a peer that does not speak the
      // protocol. Bounded rather than `readBytes()`, which would grow until the socket closes.
      val buffer = java.io.ByteArrayOutputStream()
      val chunk = ByteArray(8192)
      while (true) {
        val read = input.read(chunk)
        if (read <= 0) break
        if (buffer.size() + read > MAX_RESPONSE_BYTES) {
          throw SyncTransportException(SyncFailureReason.PROTOCOL, "响应过大")
        }
        buffer.write(chunk, 0, read)
      }
      buffer.toString("UTF-8")
    }
    return RawResponse(status, body)
  }

  private fun readLine(input: InputStream): String? {
    val builder = StringBuilder()
    while (true) {
      val byte = input.read()
      if (byte == -1) return if (builder.isEmpty()) null else builder.toString()
      if (byte == '\n'.code) break
      if (byte != '\r'.code) builder.append(byte.toChar())
    }
    return builder.toString()
  }

  private data class RawResponse(val status: Int, val body: String)

  companion object {
    const val DEFAULT_TIMEOUT_MILLIS = 8_000

    /**
     * Connect budget for the ordinary exchange.
     *
     * Long enough for a busy Wi-Fi to complete a handshake, short enough that a dead address
     * does not own the sync button for the whole read budget.
     */
    const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 3_000

    /**
     * Upper bound on parts fetched for one scope in a single pull.
     *
     * 500 records per part is the engine's cap, so this admits 500,000 records — far beyond
     * any real scope. It exists only to stop a misbehaving peer from making the client
     * loop forever.
     */
    private const val MAX_PULL_CHUNKS = 1_000

    /**
     * Upper bound on one response body read from a peer.
     *
     * Matches the server's own request bound ([com.livehub.core.sync.server.SyncHttpServer.MAX_BODY_BYTES]):
     * a chunk of 500 records is tens of kilobytes, so anything near this is either a misbehaving
     * peer or an address that answered with something that is not this protocol. Bounding it here
     * matters because this side reads from addresses that have not been authenticated yet — the
     * pairing sweep offers the code to an entire subnet.
     */
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
  }
}

/** Adapts [LanSyncTransport] to the generic [SyncHttpTransport] shape. */
class LanSyncHttpTransport(
  private val transport: LanSyncTransport,
  private val addressProvider: (TrustedPeer) -> DiscoveredPeer?,
) : SyncHttpTransport {
  override suspend fun call(
    peer: TrustedPeer,
    host: String,
    port: Int,
    path: String,
    envelope: SyncEnvelope,
    timeoutMillis: Int?,
  ): SyncEnvelope = transport.call(peer, host, port, path, envelope, timeoutMillis)
}
