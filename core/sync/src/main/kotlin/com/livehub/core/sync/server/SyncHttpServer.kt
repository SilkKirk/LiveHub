package com.livehub.core.sync.server

import com.livehub.core.sync.SyncEnvelope
import com.livehub.core.sync.SyncPayload
import com.livehub.core.sync.SyncScope
import com.livehub.core.sync.TrustedPeer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A deliberately tiny HTTP/1.1 server for LAN sync.
 *
 * It speaks exactly four endpoints, one connection at a time, and closes the socket
 * after each response. That is more than enough for a phone-to-phone exchange a few
 * hundred kilobytes in size, and it avoids pulling a full server framework into the
 * APK. Ktor was considered and rejected for that reason.
 */
class SyncHttpServer(
  private val deviceId: Long,
  private val deviceName: String,
  private val localSecret: String,
  private val handler: Handler,
) {
  /** Implemented by the app to serve payloads from the sync engine. */
  interface Handler {
    /**
     * Validates a peer that is trying to pair.
     *
     * Returns a [PairingOutcome] rather than a nullable secret so a rejection can say
     * *why*: "配对码已过期" and "配对码不正确" ask different things of the user, and the
     * caller cannot guess which happened from a bare null.
     */
    fun onPair(envelope: SyncEnvelope, remoteAddress: String): PairingOutcome

    /**
     * Returns one part of what this device knows about [scope], or null when it is disabled.
     *
     * [chunkIndex] is 0 for the first (and, for most scopes, only) part. The returned
     * payload carries `chunkCount`/`chunkIndex` so the caller knows whether to ask again.
     */
    fun onPull(
      envelope: SyncEnvelope,
      scope: SyncScope,
      peer: TrustedPeer?,
      chunkIndex: Int,
    ): SyncPayload?

    /** Applies a payload pushed by a peer; returns this device's payload in exchange. */
    fun onPush(envelope: SyncEnvelope, payload: SyncPayload, peer: TrustedPeer?): SyncPayload?

    /** Looks up a paired peer by device id. */
    fun peerById(deviceId: Long): TrustedPeer?
  }

  private val running = AtomicBoolean(false)

  @Volatile private var serverSocket: ServerSocket? = null

  @Volatile var port: Int = DEFAULT_PORT
    private set

  val isRunning: Boolean get() = running.get()

  /** Starts listening; safe to call twice. */
  fun start(preferredPort: Int = DEFAULT_PORT): Int {
    if (running.getAndSet(true)) return port
    val socket = bind(preferredPort)
    serverSocket = socket
    port = socket.localPort
    thread(name = "livehub-sync-server", isDaemon = true) {
      while (running.get() && !socket.isClosed) {
        try {
          val client = socket.accept()
          thread(name = "livehub-sync-conn", isDaemon = true) { serve(client) }
        } catch (error: SocketException) {
          // Socket closed during shutdown.
          break
        } catch (error: Exception) {
          if (!running.get()) break
        }
      }
    }
    return port
  }

  fun stop() {
    running.set(false)
    runCatching { serverSocket?.close() }
    serverSocket = null
  }

  private fun bind(preferredPort: Int): ServerSocket = runCatching {
    ServerSocket().apply {
      reuseAddress = true
      bind(InetSocketAddress(preferredPort))
    }
  }.getOrElse {
    // Fall back to an ephemeral port when the default is taken.
    ServerSocket(0)
  }

  private fun serve(client: Socket) {
    client.use { socket ->
      socket.soTimeout = SOCKET_TIMEOUT_MILLIS
      val input = BufferedInputStream(socket.getInputStream())
      val output = BufferedOutputStream(socket.getOutputStream())

      val request = parseRequest(input) ?: run {
        writeResponse(output, 400, """{"error":"bad request"}""")
        return
      }

      val body = when (request.path) {
        SyncEnvelope.PATH_PING -> handlePing(request)
        SyncEnvelope.PATH_PULL -> handlePull(request)
        SyncEnvelope.PATH_PUSH -> handlePush(request)
        SyncEnvelope.PATH_PAIR -> handlePair(request, socket)
        else -> Response(404, """{"error":"not found"}""")
      }

      writeResponse(output, body.status, body.body)
      output.flush()
    }
  }

  private fun handlePing(request: Request): Response {
    val envelope = decode(request.body) ?: return Response(400, errorBody("bad envelope"))
    return Response(
      200,
      SyncEnvelope.encode(
        SyncEnvelope(
          deviceId = deviceId,
          deviceName = deviceName,
          secret = "",
          serverDeviceId = deviceId,
          serverDeviceName = deviceName,
        ),
      ),
    )
  }

  /**
   * Completes a pairing handshake.
   *
   * The exchange is symmetric: each device owns a secret and the other side stores it.
   *
   *  * the **caller** sends `secret` = the secret it issued for itself,
   *  * the **host** replies with `secret` = the secret it issued for itself.
   *
   * After a successful round trip the caller can authenticate against the host (the host
   * stored the caller's secret) and the host can authenticate against the caller (the
   * caller stored the host's secret). Having each side invent its own idea of the shared
   * secret — the previous behaviour — left every authenticated request failing.
   */
  private fun handlePair(request: Request, socket: Socket): Response {
    val envelope = decode(request.body) ?: return Response(400, errorBody("bad envelope"))
    val remote = socket.inetAddress?.hostAddress.orEmpty()
    val outcome = handler.onPair(envelope, remote)

    val (issuedSecret, peer) = when (outcome) {
      is PairingOutcome.Accepted -> outcome.issuedSecret to outcome.peer
      // 403 with the reason in the envelope: the pairing client turns this into the exact
      // message the user needs ("配对码已过期…"), instead of a generic "连线失败".
      is PairingOutcome.Rejected -> return Response(403, errorBody(outcome.reason))
    }

    return Response(
      200,
      SyncEnvelope.encode(
        SyncEnvelope(
          deviceId = deviceId,
          deviceName = deviceName,
          // Hand the caller the secret it must present to us next time.
          secret = issuedSecret,
          serverDeviceId = deviceId,
          serverDeviceName = deviceName,
          preferredPort = port,
        ),
      ),
    )
  }

  private fun handlePull(request: Request): Response {
    val envelope = decode(request.body) ?: return Response(400, errorBody("bad envelope"))
    val peer = authenticate(envelope)
    if (peer == null && !acceptsUnpaired(envelope)) {
      return Response(403, errorBody("未配对"))
    }
    val scope = request.query["scope"]?.let { SyncScope.fromWireName(it) }
      ?: return Response(400, errorBody("bad scope"))
    // A scope larger than one payload is fetched piecewise: the caller passes `chunk`, gets
    // back that part plus its `chunkCount`, and repeats while `hasMoreChunks` holds. The
    // older single-shot form (no `chunk`) returns part 0, so an app that has not been
    // updated still works — it simply sees the first page.
    val chunkIndex = request.query["chunk"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    // No payload means this device does not share the scope — either the user switched it off
    // or there is no such target. Both are answered with a *successful* HTTP status plus a
    // machine readable code: the scope is simply not on the table, so the caller must skip it
    // rather than show "对方返回 HTTP 404" and count a failure.
    val payload = handler.onPull(envelope, scope, peer, chunkIndex)
      ?: return Response(200, scopeDisabledBody(scope))

    return Response(
      200,
      SyncEnvelope.encode(
        SyncEnvelope(
          deviceId = deviceId,
          deviceName = deviceName,
          secret = "",
          serverDeviceId = deviceId,
          serverDeviceName = deviceName,
          preferredPort = port,
          payload = payload,
        ),
      ),
    )
  }

  private fun handlePush(request: Request): Response {
    val envelope = decode(request.body) ?: return Response(400, errorBody("bad envelope"))
    val payload = envelope.payload ?: return Response(400, errorBody("missing payload"))
    val peer = authenticate(envelope)
    if (peer == null && !acceptsUnpaired(envelope)) {
      return Response(403, errorBody("未配对"))
    }
    // Writers and settings look different from here, so the decision belongs to the handler.
    // What matters is that a refusal is *not* an error response: nothing failed.
    val response = try {
      handler.onPush(envelope, payload, peer)
    } catch (disabled: ScopeDisabledException) {
      return Response(200, scopeDisabledBody(disabled.scope))
    }
    return Response(
      200,
      SyncEnvelope.encode(
        SyncEnvelope(
          deviceId = deviceId,
          deviceName = deviceName,
          secret = "",
          serverDeviceId = deviceId,
          serverDeviceName = deviceName,
          preferredPort = port,
          payload = response,
        ),
      ),
    )
  }

  private fun scopeDisabledBody(scope: SyncScope): String =
    SyncEnvelope.encode(
      SyncEnvelope(
        deviceId = deviceId,
        deviceName = deviceName,
        secret = "",
        serverDeviceId = deviceId,
        serverDeviceName = deviceName,
        preferredPort = port,
        error = "未共享该同步范围: ${scope.displayName}",
        errorCode = SyncEnvelope.ERROR_SCOPE_DISABLED,
      ),
    )

  /** A peer is trusted when it presents the secret issued during pairing. */
  private fun authenticate(envelope: SyncEnvelope): TrustedPeer? {
    val peer = handler.peerById(envelope.deviceId) ?: return null
    return if (peer.sharedSecret == envelope.secret) peer else null
  }

  /**
   * Pairing itself is unauthenticated by design — the six digit code *is* the
   * credential and it expires after a few minutes.
   */
  private fun acceptsUnpaired(envelope: SyncEnvelope): Boolean =
    envelope.secret.isBlank() && handler.peerById(envelope.deviceId) == null

  private fun decode(body: String): SyncEnvelope? =
    runCatching { SyncEnvelope.decode(body) }.getOrNull()

  private fun errorBody(message: String): String =
    SyncEnvelope.encode(
      SyncEnvelope(
        deviceId = deviceId,
        deviceName = deviceName,
        secret = "",
        serverDeviceId = deviceId,
        serverDeviceName = deviceName,
        error = message,
      ),
    )

  private fun parseRequest(input: InputStream): Request? {
    val requestLine = readLine(input) ?: return null
    val parts = requestLine.split(' ')
    if (parts.size < 2) return null
    val path = parts[1].substringBefore('?')
    val query = parts[1].substringAfter('?', "")
      .split('&')
      .mapNotNull { pair ->
        val index = pair.indexOf('=')
        if (index <= 0) null else pair.substring(0, index) to pair.substring(index + 1)
      }
      .toMap()

    var contentLength = 0
    while (true) {
      val line = readLine(input) ?: break
      if (line.isEmpty()) break
      if (line.startsWith("Content-Length:", ignoreCase = true)) {
        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
      }
    }

    val body = if (contentLength > 0) {
      val buffer = ByteArray(contentLength)
      var total = 0
      while (total < contentLength) {
        val read = input.read(buffer, total, contentLength - total)
        if (read <= 0) break
        total += read
      }
      String(buffer, 0, total, Charsets.UTF_8)
    } else {
      ""
    }

    return Request(path = path, query = query, body = body)
  }

  private fun readLine(input: InputStream): String? {
    val builder = StringBuilder()
    while (true) {
      val byte = input.read()
      if (byte == -1) return if (builder.isEmpty()) null else builder.toString()
      if (byte == '\n'.code) break
      if (byte != '\r'.code) builder.append(byte.toChar())
      if (builder.length > MAX_LINE_LENGTH) return null
    }
    return builder.toString()
  }

  private fun writeResponse(output: OutputStream, status: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val header = buildString {
      append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
      append("Content-Type: application/json; charset=utf-8\r\n")
      append("Content-Length: ").append(bytes.size).append("\r\n")
      append("Connection: close\r\n")
      append("\r\n")
    }
    output.write(header.toByteArray(Charsets.UTF_8))
    output.write(bytes)
  }

  private fun reason(status: Int): String = when (status) {
    200 -> "OK"
    400 -> "Bad Request"
    403 -> "Forbidden"
    404 -> "Not Found"
    else -> "Error"
  }

  private data class Request(val path: String, val query: Map<String, String>, val body: String)

  private data class Response(val status: Int, val body: String)

  companion object {
    const val DEFAULT_PORT = 47653
    private const val SOCKET_TIMEOUT_MILLIS = 15_000
    private const val MAX_LINE_LENGTH = 8192

    /** Best-effort LAN IPv4 address of this device. */
    fun localIpv4Addresses(): List<String> = try {
      NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filterNot { it.isLoopbackAddress }
        .mapNotNull { it.hostAddress }
    } catch (error: Exception) {
      emptyList()
    }
  }
}

/**
 * What the app decided about a pairing attempt.
 *
 * [Rejected] carries a user presentable [reason] that travels back over the wire, so the
 * device typing the code can say "配对码已过期" instead of a generic connection error.
 */
sealed interface PairingOutcome {
  data class Accepted(val issuedSecret: String, val peer: TrustedPeer) : PairingOutcome

  data class Rejected(val reason: String) : PairingOutcome
}

/**
 * Thrown by a [SyncHttpServer.Handler] to refuse work for a scope this device does not share.
 *
 * The user turned that switch off, so the data must not be written and the peer must not be
 * told anything went wrong. The server answers with [SyncEnvelope.ERROR_SCOPE_DISABLED], which
 * the caller turns into "skipped", not "failed".
 */
class ScopeDisabledException(val scope: SyncScope) : Exception("未共享该同步范围: ${scope.displayName}")

