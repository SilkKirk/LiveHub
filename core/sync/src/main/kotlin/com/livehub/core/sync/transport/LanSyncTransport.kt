package com.livehub.core.sync.transport

import com.livehub.core.sync.DiscoveredPeer
import com.livehub.core.sync.SyncEnvelope
import com.livehub.core.sync.SyncHttpTransport
import com.livehub.core.sync.SyncPayload
import com.livehub.core.sync.SyncScope
import com.livehub.core.sync.SyncTransport
import com.livehub.core.sync.SyncTransportException
import com.livehub.core.sync.SyncFailureReason
import com.livehub.core.sync.TrustedPeer
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
  /** Port this device listens on; advertised so peers can call back. */
  private val localPort: Int = 0,
) : SyncTransport {

  override suspend fun pull(peer: TrustedPeer, scope: SyncScope): SyncPayload? {
    val address = addressProvider(peer) ?: throw SyncTransportException(
      SyncFailureReason.REFUSED,
      "${peer.deviceName} 不在线",
    )
    val envelope = SyncEnvelope(
      deviceId = deviceId,
      deviceName = deviceName,
      // Present our own secret: the peer stored exactly this during pairing.
      secret = localSecret,
      preferredPort = localPort,
    )
    val response = call(
      peer = peer,
      host = address.host,
      port = address.port,
      path = "${SyncEnvelope.PATH_PULL}?scope=${scope.wireName}",
      envelope = envelope,
    )
    response.error?.let { throw SyncTransportException(SyncFailureReason.PROTOCOL, it) }
    return response.payload
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
  ): SyncEnvelope {
    val body = SyncEnvelope.encode(envelope)
    val response = try {
      Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), timeoutMillis)
        socket.soTimeout = timeoutMillis

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
      throw SyncTransportException(SyncFailureReason.UNKNOWN, error.message ?: "同步失败", error)
    }

    if (response.status == 403) {
      throw SyncTransportException(SyncFailureReason.UNAUTHORIZED, "配对已失效，请重新配对")
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
      val buffer = ByteArray(contentLength)
      var total = 0
      while (total < contentLength) {
        val read = input.read(buffer, total, contentLength - total)
        if (read <= 0) break
        total += read
      }
      String(buffer, 0, total, Charsets.UTF_8)
    } else {
      input.readBytes().toString(Charsets.UTF_8)
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
  ): SyncEnvelope = transport.call(peer, host, port, path, envelope)
}
