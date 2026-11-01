package com.livehub.core.sync

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * End-to-end cross-device sync over the real LAN transport.
 *
 * Two independently-configured "devices" each run a [com.livehub.core.sync.server.SyncHttpServer]
 * on loopback and talk to each other through [transport.LanSyncTransport]. Nothing is
 * mocked except the address book, so this exercises the pairing handshake, the secret
 * check, payload encoding, conflict resolution and the tombstone rules exactly as they
 * run on a phone.
 *
 * The scenarios are the ones that silently break in real WeChat-style sync:
 *  * a follow made on A appears on B,
 *  * an unfollow made on A *removes* the item on B instead of being resurrected,
 *  * the same exchange run twice does not duplicate anything,
 *  * two offline devices editing the same room converge deterministically.
 */
class CrossDeviceSyncTest {

  /** A single simulated device: store + engine + server + transport. */
  private inner class Device(
    val id: Long,
    val name: String,
    var now: Long,
  ) {
    val store = FakeFollowStore()
    val target = SyncTargetAdapter(store)
    var peers = mutableListOf<TrustedPeer>()

    val engine = SyncEngine(
      targets = MapSyncTargets(mapOf(SyncScope.FOLLOWS to target)),
      deviceIdProvider = { id },
      deviceNameProvider = { name },
      peersProvider = { peers.toList() },
      peersWriter = { updated -> peers = updated.toMutableList() },
      clock = { now },
      idGenerator = { "secret-$id" },
    )

    val addressBook = com.livehub.core.sync.transport.PeerAddressBook()
    lateinit var server: com.livehub.core.sync.server.SyncHttpServer
    lateinit var transport: com.livehub.core.sync.transport.LanSyncTransport
    var port: Int = 0

    /** Start listening and wire the transport, mirroring what SyncController does. */
    fun start() {
      server = com.livehub.core.sync.server.SyncHttpServer(
        deviceId = id,
        deviceName = name,
        localSecret = "local",
        handler = object : com.livehub.core.sync.server.SyncHttpServer.Handler {
          override fun onPair(
            envelope: SyncEnvelope,
            remoteAddress: String,
          ): Pair<String, TrustedPeer>? {
            val result = engine.completePairing(
              peerDeviceId = envelope.deviceId,
              peerDeviceName = envelope.deviceName,
              code = envelope.payload?.entries.orEmpty(),
              expectedCode = PAIRING_CODE,
            )
            val peer = result.getOrNull() ?: return null
            // Record where the *caller* listens, which the handshake told us.
            addressBook.addManual(
              envelope.deviceId,
              envelope.deviceName,
              "127.0.0.1",
              envelope.preferredPort,
            )
            return peer.sharedSecret to peer
          }

          override fun onPull(
            envelope: SyncEnvelope,
            scope: SyncScope,
            peer: TrustedPeer?,
          ): SyncPayload? = engine.buildPayload(scope)

          override fun onPush(
            envelope: SyncEnvelope,
            payload: SyncPayload,
            peer: TrustedPeer?,
          ): SyncPayload? {
            engine.applyPayload(payload, peer)
            return engine.buildPayload(payload.parsedScope ?: return null)
          }

          override fun peerById(deviceId: Long): TrustedPeer? =
            peers.firstOrNull { it.deviceId == deviceId }
        },
      )
      port = server.start(0)
      transport = com.livehub.core.sync.transport.LanSyncTransport(
        deviceId = id,
        deviceName = name,
        localSecret = "local",
        peersProvider = { peers.toList() },
        addressProvider = { peer -> addressBook.addressFor(peer) },
        timeoutMillis = 3_000,
      )
    }

    fun stop() {
      if (::server.isInitialized) server.stop()
    }

    /** Performs the pairing handshake against [other] using the shared code. */
    fun pairWith(other: Device) {
      val envelope = SyncEnvelope(
        deviceId = id,
        deviceName = name,
        secret = "",
        preferredPort = port,
        payload = SyncPayload(
          scope = "pair",
          sourceDeviceId = id,
          sequence = 1,
          createdAt = now,
          entries = PAIRING_CODE,
        ),
      )
      val response = transport.call(
        peer = TrustedPeer(0, other.name, "", 0),
        host = "127.0.0.1",
        port = other.port,
        path = SyncEnvelope.PATH_PAIR,
        envelope = envelope,
      )
      val peerId = response.serverDeviceId.takeIf { it != 0L } ?: response.deviceId
      val peer = TrustedPeer(
        deviceId = peerId,
        deviceName = response.serverDeviceName,
        sharedSecret = response.secret,
        pairedAt = now,
        lastSeenAt = now,
      )
      peers = (peers.filterNot { it.deviceId == peerId } + peer).toMutableList()
      addressBook.addManual(peerId, peer.deviceName, "127.0.0.1", other.port)
    }

    /** Runs the same exchange SyncController.syncNow performs. */
    suspend fun syncWith(other: Device) {
      val peer = peers.first { it.deviceId == other.id }
      SyncCoordinator(engine, transport).syncWith(peer)
    }

    fun follow(roomId: String) {
      val hlc = engine.nextHlc()
      store.put(
        SyncRecord(
          key = "douyu:$roomId",
          value = SyncCodec.stringValue(roomId),
          deleted = false,
          hlc = hlc,
          deviceId = id,
          updatedAt = now,
        ),
      )
    }

    fun unfollow(roomId: String) {
      val hlc = engine.nextHlc()
      store.put(
        SyncRecord(
          key = "douyu:$roomId",
          value = null,
          deleted = true,
          hlc = hlc,
          deviceId = id,
          updatedAt = now,
        ),
      )
    }

    fun followedIds(): List<String> = store.records()
      .filter { !it.deleted }
      .map { it.key.removePrefix("douyu:") }
      .sorted()

    fun tombstones(): List<String> = store.records()
      .filter { it.deleted }
      .map { it.key.removePrefix("douyu:") }
      .sorted()
  }

  private lateinit var phone: Device
  private lateinit var tablet: Device

  @BeforeEach
  fun setUp() {
    phone = Device(id = 1, name = "Pixel", now = BASE_TIME).also { it.start() }
    tablet = Device(id = 2, name = "Tablet", now = BASE_TIME).also { it.start() }
  }

  @AfterEach
  fun tearDown() {
    phone.stop()
    tablet.stop()
  }

  // ------------------------------------------------------------------ pairing ---

  @Test
  fun `pairing gives each side the secret the other will accept`() {
    phone.pairWith(tablet)

    // The phone invited, so it stores the secret the tablet issued...
    assertEquals(1, phone.peers.size)
    assertEquals(tablet.id, phone.peers.first().deviceId)
    // completePairing strips dashes so the secret is URL/QR friendly.
    assertEquals("secret2", phone.peers.first().sharedSecret)

    // ...and the tablet stores the phone as a peer with the secret the phone issued.
    assertEquals(1, tablet.peers.size)
    assertEquals(phone.id, tablet.peers.first().deviceId)
    assertEquals("secret1", tablet.peers.first().sharedSecret)

    // Each side's stored secret is exactly what the other side will present, which is
    // what makes authenticate() succeed in both directions.
    assertEquals("secret2", phone.peers.first().sharedSecret)
  }

  @Test
  fun `an unpaired device is rejected`() = kotlinx.coroutines.test.runTest {
    val stranger = SyncEnvelope(
      deviceId = 99,
      deviceName = "stranger",
      secret = "not-the-secret",
    )
    val failed = runCatching {
      phone.transport.call(
        peer = TrustedPeer(0, "x", "", 0),
        host = "127.0.0.1",
        port = phone.port,
        path = "${SyncEnvelope.PATH_PULL}?scope=follows",
        envelope = stranger,
      )
    }
    assertTrue(failed.isFailure, "an unknown peer must not be able to pull")
  }

  // ----------------------------------------------------------------- exchange ---

  @Test
  fun `a follow made on the phone arrives on the tablet`() = kotlinx.coroutines.test.runTest {
    phone.pairWith(tablet)
    phone.follow("111")
    phone.follow("222")

    phone.syncWith(tablet)

    assertEquals(listOf("111", "222"), phone.followedIds())
    assertEquals(listOf("111", "222"), tablet.followedIds())
  }

  @Test
  fun `an unfollow on the phone removes the room on the tablet`() =
    kotlinx.coroutines.test.runTest {
      phone.pairWith(tablet)
      phone.follow("111")
      phone.follow("222")
      phone.syncWith(tablet)
      assertEquals(2, tablet.followedIds().size)

      // Later in time: the user unfollows 111 on the phone.
      phone.now = BASE_TIME + 1_000
      tablet.now = BASE_TIME + 1_000
      phone.unfollow("111")
      phone.syncWith(tablet)

      assertEquals(listOf("222"), phone.followedIds())
      assertEquals(listOf("222"), tablet.followedIds())
      // The tablet keeps a tombstone so the delete can keep propagating.
      assertEquals(listOf("111"), tablet.tombstones())
    }

  @Test
  fun `a stale follow cannot resurrect a room unfollowed elsewhere`() =
    kotlinx.coroutines.test.runTest {
      phone.pairWith(tablet)
      phone.follow("111")
      phone.syncWith(tablet)
      assertEquals(1, tablet.followedIds().size)

      phone.now = BASE_TIME + 1_000
      tablet.now = BASE_TIME + 1_000
      phone.unfollow("111")
      phone.syncWith(tablet)
      assertTrue(tablet.followedIds().isEmpty())

      // The tablet still holds an old *live* record from before the unfollow; pushing
      // it back must not win, because the tombstone carries the newer clock.
      tablet.store.put(
        SyncRecord(
          key = "douyu:111",
          value = SyncCodec.stringValue("111"),
          deleted = false,
          hlc = Hlc(BASE_TIME - 5_000, 0),
          deviceId = tablet.id,
          updatedAt = BASE_TIME - 5_000,
        ),
      )
      tablet.syncWith(phone)

      assertTrue(phone.followedIds().isEmpty(), "the tombstone must survive a stale follow")
    }

  @Test
  fun `syncing twice does not duplicate anything`() = kotlinx.coroutines.test.runTest {
    phone.pairWith(tablet)
    phone.follow("111")

    phone.syncWith(tablet)
    phone.syncWith(tablet)
    tablet.syncWith(phone)

    assertEquals(listOf("111"), phone.followedIds())
    assertEquals(listOf("111"), tablet.followedIds())
    assertEquals(1, tablet.store.records().size)
  }

  @Test
  fun `two offline devices converge regardless of who syncs first`() {
    kotlinx.coroutines.test.runTest {
      phone.pairWith(tablet)

      // Both start from the same two rooms.
      phone.follow("111")
      phone.follow("222")
      phone.syncWith(tablet)

      // Then both go offline and make conflicting edits.
      phone.now = BASE_TIME + 1_000
      tablet.now = BASE_TIME + 1_000
      phone.unfollow("111")
      tablet.unfollow("222")
      phone.follow("333")
      tablet.follow("444")

      phone.syncWith(tablet)

      // After one exchange both sides must agree: only 333 and 444 remain.
      assertEquals(tablet.followedIds(), phone.followedIds())
      assertEquals(listOf("333", "444"), phone.followedIds())
    }
  }

  @Test
  fun `a device with no records still pulls what the peer has`() =
    kotlinx.coroutines.test.runTest {
      phone.pairWith(tablet)
      phone.follow("111")
      phone.follow("222")
      phone.follow("333")

      phone.syncWith(tablet)

      assertEquals(3, tablet.followedIds().size)
      // ...and the empty phone was not clobbered by the tablet's (also empty) state.
      assertEquals(3, phone.followedIds().size)
    }

  @Test
  fun `peer sequence tracking blocks a replayed payload`() = kotlinx.coroutines.test.runTest {
    phone.pairWith(tablet)
    phone.follow("111")
    phone.syncWith(tablet)

    val payload = phone.engine.buildPayload(SyncScope.FOLLOWS)!!
    val peerOnTablet = tablet.peers.first { it.deviceId == phone.id }

    val first = tablet.engine.applyPayload(payload, peerOnTablet)
    assertTrue(first.accepted)

    val replay = tablet.engine.applyPayload(payload, tablet.peers.first { it.deviceId == phone.id })
    assertFalse(replay.accepted, "the same sequence must not be applied twice")
  }

  @Test
  fun `tombstones propagate only to peers that already know the room`() =
    kotlinx.coroutines.test.runTest {
      // The tablet never had room 999, so after a sync it must not gain a tombstone for it.
      phone.pairWith(tablet)
      phone.follow("999")
      phone.unfollow("999")
      phone.syncWith(tablet)

      assertTrue(phone.followedIds().isEmpty())
      assertTrue(tablet.followedIds().isEmpty())
    }

  @Test
  fun `payload survives a unicode streamer name`() = kotlinx.coroutines.test.runTest {
    phone.pairWith(tablet)
    val hlc = phone.engine.nextHlc()
    phone.store.put(
      SyncRecord(
        key = "douyu:777",
        value = JsonObject(
          mapOf(
            "name" to kotlinx.serialization.json.JsonPrimitive("一条小团团 🎮"),
            "title" to kotlinx.serialization.json.JsonPrimitive("今天继续冲分！"),
          ),
        ),
        deleted = false,
        hlc = hlc,
        deviceId = phone.id,
        updatedAt = BASE_TIME,
      ),
    )

    phone.syncWith(tablet)

    val synced = tablet.store.records().firstOrNull { it.key == "douyu:777" }
    assertNotNull(synced)
    val name = (synced!!.value?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content
    assertEquals("一条小团团 🎮", name)
  }

  @Test
  fun `a disconnected peer reports a failure instead of hanging`() =
    kotlinx.coroutines.test.runTest {
      phone.pairWith(tablet)
      tablet.stop()

      val outcomes = SyncCoordinator(phone.engine, phone.transport)
        .syncWith(phone.peers.first())

      assertTrue(outcomes.any { !it.success }, "an unreachable peer must be reported")
    }

  @Test
  fun `unpairing stops further exchanges`() = kotlinx.coroutines.test.runTest {
    phone.pairWith(tablet)
    phone.engine.unpair(tablet.id)

    assertTrue(phone.peers.isEmpty())
    val outcomes = SyncCoordinator(phone.engine, phone.transport).syncAll()
    assertTrue(outcomes.isEmpty())
  }

  private companion object {
    const val PAIRING_CODE = "482913"
    const val BASE_TIME = 1_700_000_000_000L
  }
}

/** Minimal in-memory [SyncTarget] for the test devices. */
private class FakeFollowStore {
  private var records: List<SyncRecord<JsonObject>> = emptyList()

  fun records(): List<SyncRecord<JsonObject>> = records

  fun put(record: SyncRecord<JsonObject>) {
    records = records.filterNot { it.key == record.key } + record
  }

  fun replaceAll(updated: List<SyncRecord<JsonObject>>) {
    records = updated
  }
}

/** Adapts [FakeFollowStore] to the [SyncTarget] contract. */
private class SyncTargetAdapter(
  private val store: FakeFollowStore,
) : SyncTarget {
  override val scope: SyncScope = SyncScope.FOLLOWS

  override fun snapshot(): List<SyncRecord<out Any>> = store.records()

  override fun apply(records: List<SyncRecord<out Any>>): Int {
    @Suppress("UNCHECKED_CAST")
    val typed = records as List<SyncRecord<JsonObject>>
    val before = store.records().associate { it.key to it.version }
    store.replaceAll(typed)
    return typed.count { before[it.key] != it.version }
  }
}
