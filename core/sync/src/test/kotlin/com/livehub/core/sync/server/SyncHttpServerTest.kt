package com.livehub.core.sync.server

import com.livehub.core.sync.DiscoveredPeer
import com.livehub.core.sync.SyncEnvelope
import com.livehub.core.sync.SyncFailureReason
import com.livehub.core.sync.SyncPayload
import com.livehub.core.sync.SyncScope
import com.livehub.core.sync.SyncTransportException
import com.livehub.core.sync.TrustedPeer
import com.livehub.core.sync.server.PairingOutcome
import com.livehub.core.sync.transport.LanSyncTransport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class SyncHttpServerTest {
  private lateinit var server: SyncHttpServer
  private var port = 0
  private val paired = mutableListOf(
    TrustedPeer(deviceId = 2, deviceName = "tablet", sharedSecret = "sekret", pairedAt = 0),
  )
  private var pullCalls = 0
  private var pushCalls = 0
  private var lastPushed: SyncPayload? = null
  private var expectedPairCode: String? = "123456"
  private var pullDisabled = false
  private var pushRefuses = false

  @BeforeEach
  fun setUp() {
    server = SyncHttpServer(
      deviceId = 1,
      deviceName = "phone",
      handler = object : SyncHttpServer.Handler {
        override fun onPair(envelope: SyncEnvelope, remoteAddress: String): PairingOutcome {
          val expected = expectedPairCode ?: return PairingOutcome.Rejected("本机尚未生成配对码")
          if (envelope.payload?.entries != expected) return PairingOutcome.Rejected("配对码不正确")
          val peer = TrustedPeer(
            deviceId = envelope.deviceId,
            deviceName = envelope.deviceName,
            sharedSecret = "issued-secret",
            pairedAt = 0,
          )
          paired += peer
          return PairingOutcome.Accepted(issuedSecret = "issued-secret", peer = peer)
        }

        override fun onPull(
          envelope: SyncEnvelope,
          scope: SyncScope,
          peer: TrustedPeer?,
          chunkIndex: Int,
        ): SyncPayload? {
          pullCalls++
          if (pullDisabled) return null
          return SyncPayload(
            scope = scope.wireName,
            sourceDeviceId = 1,
            sequence = 1,
            createdAt = 0,
            entries = "[]",
            chunkIndex = chunkIndex,
            chunkCount = 1,
          )
        }

        override fun onPush(envelope: SyncEnvelope, payload: SyncPayload, peer: TrustedPeer?): SyncPayload? {
          pushCalls++
          if (pushRefuses) throw ScopeDisabledException(payload.parsedScope ?: SyncScope.FOLLOWS)
          lastPushed = payload
          return null
        }

        override fun peerById(deviceId: Long): TrustedPeer? = paired.firstOrNull { it.deviceId == deviceId }
      },
    )
    port = server.start(0)
  }

  @AfterEach
  fun tearDown() {
    server.stop()
  }

  @Test
  fun `server binds an ephemeral port and reports it`() {
    assertTrue(server.isRunning)
    assertTrue(port > 0)
  }

  @Test
  fun `ping returns the server identity`() {
    val response = post(
      SyncEnvelope.PATH_PING,
      SyncEnvelope.encode(SyncEnvelope(deviceId = 2, deviceName = "tablet", secret = "")),
    )
    val envelope = SyncEnvelope.decode(response.body)
    assertEquals(1L, envelope.serverDeviceId)
    assertEquals("phone", envelope.serverDeviceName)
    assertNull(envelope.error)
  }

  @Test
  fun `pull rejects an unpaired peer`() {
    val response = post(
      "${SyncEnvelope.PATH_PULL}?scope=follows",
      SyncEnvelope.encode(SyncEnvelope(deviceId = 99, deviceName = "stranger", secret = "wrong")),
    )
    assertEquals(403, response.status)
  }

  /**
   * The hole the test above could not see: an **empty** secret.
   *
   * `acceptsUnpaired` used to be consulted on the data paths as well as on pairing, and its
   * whole condition was `secret.isBlank() && peerById(deviceId) == null`. So a request that
   * simply omitted the secret — from a device id this installation had never heard of — was
   * waved through, and `GET /livehub/v1/pull?scope=credentials` returned the Bilibili and
   * Douyin session cookies to anything on the same Wi-Fi. The test above passes `"wrong"`,
   * which is non-blank, so it exercised the one branch that was already safe.
   *
   * Both halves are asserted: the status, and that the handler was never reached. A server
   * that returned 403 *after* building a payload would still have leaked it into a response.
   */
  @Test
  fun `pull rejects an unknown device that omits the secret`() {
    val response = post(
      "${SyncEnvelope.PATH_PULL}?scope=credentials",
      SyncEnvelope.encode(SyncEnvelope(deviceId = 99, deviceName = "stranger", secret = "")),
    )

    assertEquals(403, response.status)
    assertEquals(0, pullCalls, "an unpaired pull must not reach the handler")
  }

  @Test
  fun `push rejects an unknown device that omits the secret`() {
    val payload = SyncPayload(
      scope = "credentials",
      sourceDeviceId = 99,
      sequence = 1,
      createdAt = 0,
      entries = """[{"key":"login:douyin","deleted":false,"hlc":"1-0","deviceId":99,"updatedAt":1}]""",
    )
    val response = post(
      SyncEnvelope.PATH_PUSH,
      SyncEnvelope.encode(
        SyncEnvelope(deviceId = 99, deviceName = "stranger", secret = "", payload = payload),
      ),
    )

    assertEquals(403, response.status)
    assertEquals(0, pushCalls, "an unpaired push must not reach the handler")
  }

  // Pairing stays unauthenticated — that is what the six digit code is for. Covered by
  // `pairing succeeds with the correct code` below, which sends `secret = ""`. It is called out
  // here because the two tests above could otherwise be "fixed" by requiring a secret
  // everywhere, which would break the only flow that has no secret to present yet.

  @Test
  fun `pull accepts a paired peer and returns a payload`() {
    val response = post(
      "${SyncEnvelope.PATH_PULL}?scope=follows",
      SyncEnvelope.encode(SyncEnvelope(deviceId = 2, deviceName = "tablet", secret = "sekret")),
    )
    assertEquals(200, response.status)
    val envelope = SyncEnvelope.decode(response.body)
    assertNotNull(envelope.payload)
    assertEquals("follows", envelope.payload?.scope)
    assertEquals(1, pullCalls)
  }

  @Test
  fun `pull rejects an unknown scope`() {
    val response = post(
      "${SyncEnvelope.PATH_PULL}?scope=nope",
      SyncEnvelope.encode(SyncEnvelope(deviceId = 2, deviceName = "tablet", secret = "sekret")),
    )
    assertEquals(400, response.status)
  }

  @Test
  fun `push stores the payload and echoes`() {
    val payload = SyncPayload(
      scope = "follows",
      sourceDeviceId = 2,
      sequence = 5,
      createdAt = 0,
      entries = """[{"key":"a","deleted":false,"hlc":"1-0","deviceId":2,"updatedAt":1}]""",
    )
    val response = post(
      SyncEnvelope.PATH_PUSH,
      SyncEnvelope.encode(
        SyncEnvelope(deviceId = 2, deviceName = "tablet", secret = "sekret", payload = payload),
      ),
    )
    assertEquals(200, response.status)
    assertEquals(1, pushCalls)
    assertEquals(5, lastPushed?.sequence)
  }

  /**
   * A scope the host does not share is answered, not failed.
   *
   * It used to be a 404, which the client rendered as「对方返回 HTTP 404」and counted as a
   * failed sync — for every device pair where the two switches differ.
   */
  @Test
  fun `a switched off scope answers with a code instead of an error status`() {
    pullDisabled = true

    val response = post(
      "${SyncEnvelope.PATH_PULL}?scope=follows",
      SyncEnvelope.encode(SyncEnvelope(deviceId = 2, deviceName = "tablet", secret = "sekret")),
    )

    val envelope = SyncEnvelope.decode(response.body)
    assertEquals(200, response.status)
    assertEquals(SyncEnvelope.ERROR_SCOPE_DISABLED, envelope.errorCode)
    assertNull(envelope.payload)
  }

  /**
   * A push for a switched-off scope is refused and *not* stored.
   *
   * The switch used to gate only the sending side, so a peer could still overwrite a scope the
   * user had deliberately turned off on this device.
   */
  @Test
  fun `a push for a switched off scope is refused instead of stored`() {
    pushRefuses = true
    val payload = SyncPayload(
      scope = "follows",
      sourceDeviceId = 2,
      sequence = 9,
      createdAt = 0,
      entries = """[{"key":"a","deleted":false,"hlc":"1-0","deviceId":2,"updatedAt":1}]""",
    )

    val response = post(
      SyncEnvelope.PATH_PUSH,
      SyncEnvelope.encode(
        SyncEnvelope(deviceId = 2, deviceName = "tablet", secret = "sekret", payload = payload),
      ),
    )

    val envelope = SyncEnvelope.decode(response.body)
    assertEquals(200, response.status)
    assertEquals(SyncEnvelope.ERROR_SCOPE_DISABLED, envelope.errorCode)
    assertNull(lastPushed, "the payload must not reach the handler's store")
  }

  @Test
  fun `push without a payload is rejected`() {
    val response = post(
      SyncEnvelope.PATH_PUSH,
      SyncEnvelope.encode(SyncEnvelope(deviceId = 2, deviceName = "tablet", secret = "sekret")),
    )
    assertEquals(400, response.status)
  }

  @Test
  fun `pairing succeeds with the correct code`() {
    val payload = SyncPayload(
      scope = "pair",
      sourceDeviceId = 3,
      sequence = 1,
      createdAt = 0,
      entries = "123456",
    )
    val response = post(
      SyncEnvelope.PATH_PAIR,
      SyncEnvelope.encode(
        SyncEnvelope(deviceId = 3, deviceName = "tv", secret = "", payload = payload),
      ),
    )
    assertEquals(200, response.status)
    assertEquals("issued-secret", SyncEnvelope.decode(response.body).secret)
  }

  @Test
  fun `pairing fails with the wrong code`() {
    val payload = SyncPayload(
      scope = "pair",
      sourceDeviceId = 3,
      sequence = 1,
      createdAt = 0,
      entries = "000000",
    )
    val response = post(
      SyncEnvelope.PATH_PAIR,
      SyncEnvelope.encode(SyncEnvelope(deviceId = 3, deviceName = "tv", secret = "", payload = payload)),
    )
    assertEquals(403, response.status)
  }

  /**
   * A refusal has to say why.
   *
   * The pairing client turns this text into the message the user sees, so a bare 403 with
   * no body collapsed "配对码已过期" and "配对码不正确" into one unhelpful "同步失败".
   */
  @Test
  fun `a refused pairing carries the reason in the body`() {
    val payload = SyncPayload(scope = "pair", sourceDeviceId = 3, sequence = 1, createdAt = 0, entries = "000000")
    val response = post(
      SyncEnvelope.PATH_PAIR,
      SyncEnvelope.encode(SyncEnvelope(deviceId = 3, deviceName = "tv", secret = "", payload = payload)),
    )

    val envelope = SyncEnvelope.decode(response.body)
    assertEquals("配对码不正确", envelope.error)
  }

  @Test
  fun `unknown paths return 404`() {
    val response = post("/nope", "{}")
    assertEquals(404, response.status)
  }

  @Test
  fun `malformed json returns 400`() {
    val response = post(SyncEnvelope.PATH_PING, "not json at all")
    assertEquals(400, response.status)
  }

  /**
   * An oversized `Content-Length` is refused without allocating the buffer it asks for.
   *
   * The header is attacker controlled and `/pair`/`/ping` answer before authentication, so
   * `ByteArray(contentLength)` let any host on the network request a gigabyte and take the process
   * down. The server now answers 400 and never allocates — which is why this test can declare the
   * size without sending a body: the old code would have allocated it before reading anything.
   */
  @Test
  fun `an oversized declared body is refused without allocating it`() {
    val declared = SyncHttpServer.MAX_BODY_BYTES + 1
    val response = postRaw(
      path = SyncEnvelope.PATH_PING,
      contentLength = declared,
      // Deliberately empty: the server must decide from the header alone.
      body = "",
    )

    assertEquals(400, response.status)
  }

  /** A body at the bound is still accepted, so the limit is not accidentally the whole feature. */
  @Test
  fun `a body within the bound is still answered`() {
    val envelope = SyncEnvelope.encode(SyncEnvelope(deviceId = 2, deviceName = "tablet", secret = ""))
    val response = postRaw(
      path = SyncEnvelope.PATH_PING,
      contentLength = envelope.toByteArray(Charsets.UTF_8).size,
      body = envelope,
    )

    assertEquals(200, response.status)
  }

  @Test
  fun `stopping the server makes it unreachable`() {
    server.stop()
    assertTrue(!server.isRunning)
    val failed = runCatching {
      post(SyncEnvelope.PATH_PING, SyncEnvelope.encode(SyncEnvelope(deviceId = 2, deviceName = "t", secret = "")))
    }
    assertTrue(failed.isFailure)
  }

  /**
   * The refusal has to survive the real transport, not just the raw HTTP body.
   *
   * This is the join the unit tests cannot see: the server writes the code, the client maps it
   * to [SyncFailureReason.SCOPE_DISABLED]. Without that mapping the caller only has an
   * unparseable 404 (or, worse, an "unknown error") to work with.
   */
  @Test
  fun `the real client reports a switched off scope as scope disabled`() {
    pullDisabled = true
    val transport = LanSyncTransport(
      deviceId = 2,
      deviceName = "tablet",
      localSecret = "sekret",
      peersProvider = { paired },
      addressProvider = { peer -> DiscoveredPeer(peer.deviceId, peer.deviceName, "127.0.0.1", port) },
      timeoutMillis = 3_000,
    )

    val error = runCatching {
      runBlocking { transport.pull(paired.first(), SyncScope.FOLLOWS) }
    }.exceptionOrNull()

    val failure = error as? SyncTransportException
    assertEquals(SyncFailureReason.SCOPE_DISABLED, failure?.reason)
  }

  private data class Raw(val status: Int, val body: String)

  private fun post(path: String, body: String): Raw {
    Socket().use { socket ->
      socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
      socket.soTimeout = 5_000
      val out = BufferedOutputStream(socket.getOutputStream())
      val bytes = body.toByteArray(Charsets.UTF_8)
      val request = buildString {
        append("POST ").append(path).append(" HTTP/1.1\r\n")
        append("Host: 127.0.0.1\r\n")
        append("Content-Type: application/json\r\n")
        append("Content-Length: ").append(bytes.size).append("\r\n")
        append("Connection: close\r\n\r\n")
        append(body)
      }
      out.write(request.toByteArray(Charsets.UTF_8))
      out.flush()
      return readRaw(socket)
    }
  }

  /**
   * Sends a request with a `Content-Length` that does not have to match [body].
   *
   * The oversized case has to declare more than it sends: writing a real 4 MB body would make the
   * test slow without testing anything the header alone does not.
   */
  private fun postRaw(path: String, contentLength: Int, body: String): Raw {
    Socket().use { socket ->
      socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
      socket.soTimeout = 5_000
      val out = BufferedOutputStream(socket.getOutputStream())
      val request = buildString {
        append("POST ").append(path).append(" HTTP/1.1\r\n")
        append("Host: 127.0.0.1\r\n")
        append("Content-Type: application/json\r\n")
        append("Content-Length: ").append(contentLength).append("\r\n")
        append("Connection: close\r\n\r\n")
        append(body)
      }
      out.write(request.toByteArray(Charsets.UTF_8))
      out.flush()
      return readRaw(socket)
    }
  }

  private fun readRaw(socket: Socket): Raw {
    val input = BufferedInputStream(socket.getInputStream())
    val statusLine = readLine(input) ?: return Raw(0, "")
    val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
    var length = 0
    while (true) {
      val line = readLine(input) ?: break
      if (line.isEmpty()) break
      if (line.startsWith("Content-Length:", ignoreCase = true)) {
        length = line.substringAfter(':').trim().toIntOrNull() ?: 0
      }
    }
    val buffer = ByteArray(length)
    var total = 0
    while (total < length) {
      val read = input.read(buffer, total, length - total)
      if (read <= 0) break
      total += read
    }
    return Raw(status, String(buffer, 0, total, Charsets.UTF_8))
  }

  private fun readLine(input: java.io.InputStream): String? {
    val builder = StringBuilder()
    while (true) {
      val byte = input.read()
      if (byte == -1) return if (builder.isEmpty()) null else builder.toString()
      if (byte == '\n'.code) break
      if (byte != '\r'.code) builder.append(byte.toChar())
    }
    return builder.toString()
  }
}
