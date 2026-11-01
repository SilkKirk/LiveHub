package com.livehub.core.sync.server

import com.livehub.core.sync.SyncEnvelope
import com.livehub.core.sync.SyncPayload
import com.livehub.core.sync.SyncScope
import com.livehub.core.sync.TrustedPeer
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

  @BeforeEach
  fun setUp() {
    server = SyncHttpServer(
      deviceId = 1,
      deviceName = "phone",
      localSecret = "local-secret",
      handler = object : SyncHttpServer.Handler {
        override fun onPair(envelope: SyncEnvelope, remoteAddress: String): Pair<String, TrustedPeer>? {
          val expected = expectedPairCode ?: return null
          if (envelope.payload?.entries != expected) return null
          val peer = TrustedPeer(
            deviceId = envelope.deviceId,
            deviceName = envelope.deviceName,
            sharedSecret = "issued-secret",
            pairedAt = 0,
          )
          paired += peer
          return peer.sharedSecret to peer
        }

        override fun onPull(
          envelope: SyncEnvelope,
          scope: SyncScope,
          peer: TrustedPeer?,
          chunkIndex: Int,
        ): SyncPayload? {
          pullCalls++
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

  @Test
  fun `stopping the server makes it unreachable`() {
    server.stop()
    assertTrue(!server.isRunning)
    val failed = runCatching {
      post(SyncEnvelope.PATH_PING, SyncEnvelope.encode(SyncEnvelope(deviceId = 2, deviceName = "t", secret = "")))
    }
    assertTrue(failed.isFailure)
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
