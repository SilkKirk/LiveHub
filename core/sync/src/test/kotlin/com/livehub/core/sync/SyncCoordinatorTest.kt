package com.livehub.core.sync

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** In-memory transport that records every call, used to pin the exchange order. */
private class FakeTransport(
  private val remotePayloads: Map<SyncScope, SyncPayload> = emptyMap(),
  private val failOn: Set<SyncScope> = emptySet(),
) : SyncTransport {
  val calls = mutableListOf<String>()

  override suspend fun pull(peer: TrustedPeer, scope: SyncScope): SyncPayload? {
    calls += "pull:${scope.wireName}"
    if (scope in failOn) error("pull failed for $scope")
    return remotePayloads[scope]
  }

  override suspend fun push(peer: TrustedPeer, payload: SyncPayload): SyncPayload? {
    calls += "push:${payload.scope}"
    return null
  }

  override suspend fun probe(peer: TrustedPeer): Boolean {
    calls += "probe"
    return true
  }
}

class SyncCoordinatorTest {

  private var now = 10_000L

  private fun engine(
    targets: List<SyncTarget>,
    peers: MutableList<TrustedPeer>,
    deviceId: Long = 1L,
  ): SyncEngine = SyncEngine(
    targets = MapSyncTargets(targets.associateBy { it.scope }),
    deviceIdProvider = { deviceId },
    deviceNameProvider = { "device-$deviceId" },
    peersProvider = { peers.toList() },
    peersWriter = { peers.clear(); peers.addAll(it) },
    clock = { now },
  )

  private class Target(override val scope: SyncScope) : SyncTarget {
    var records: List<SyncRecord<out Any>> = emptyList()
      private set

    override fun snapshot(): List<SyncRecord<out Any>> = records

    override fun apply(records: List<SyncRecord<out Any>>): Int {
      this.records = records.toList()
      return records.size
    }
  }

  @Test
  fun `the exchange pulls before pushing for every scope`() = runTest {
    val target = Target(SyncScope.FOLLOWS)
    val peers = mutableListOf(
      TrustedPeer(deviceId = 2, deviceName = "tablet", sharedSecret = "s", pairedAt = 0),
    )
    val engine = engine(listOf(target), peers)
    target.apply(
      listOf(
        SyncRecord(key = "douyu:1", value = SyncCodec.stringValue("a"), hlc = Hlc(1, 1), deviceId = 1, updatedAt = now),
      ),
    )
    val transport = FakeTransport()

    SyncCoordinator(engine, transport).syncWith(peers.first())

    assertEquals(listOf("pull:follows", "push:follows"), transport.calls)
  }

  /**
   * A scope with nothing to send costs one request, not two.
   *
   * The push used to happen unconditionally, and it carried the entire scope even when the
   * peer already had every record — the pull result is proof of what the peer holds, so an
   * empty delta means the round trip cannot change anything.
   */
  @Test
  fun `an up to date peer is not sent an empty payload`() = runTest {
    val target = Target(SyncScope.FOLLOWS)
    val peers = mutableListOf(
      TrustedPeer(deviceId = 2, deviceName = "tablet", sharedSecret = "s", pairedAt = 0),
    )
    val engine = engine(listOf(target), peers)
    val transport = FakeTransport()

    val outcomes = SyncCoordinator(engine, transport).syncWith(peers.first())

    assertEquals(listOf("pull:follows"), transport.calls, "an empty delta must not be pushed")
    assertEquals(0, outcomes.first().pushed)
    assertTrue(outcomes.first().success)
  }

  /**
   * The reported "sent" count is the record count, not the payload sequence.
   *
   * `pushed` used to be `payload.sequence`, a monotonic per-device counter, so the sync
   * summary showed numbers that had nothing to do with the data exchanged: three followed
   * rooms with a fresh install reported "sent 1", and after a few syncs "sent 9" for the
   * same three records. The sequence exists only for de-duplication.
   */
  @Test
  fun `the pushed count is the number of records, not the sequence number`() = runTest {
    val target = Target(SyncScope.FOLLOWS)
    val peers = mutableListOf(
      TrustedPeer(deviceId = 2, deviceName = "tablet", sharedSecret = "s", pairedAt = 0),
    )
    val engine = engine(listOf(target), peers)
    // Three records on this device.
    target.apply(
      listOf(
        SyncRecord(key = "douyu:1", value = SyncCodec.stringValue("a"), hlc = Hlc(1, 1), deviceId = 1, updatedAt = now),
        SyncRecord(key = "douyu:2", value = SyncCodec.stringValue("b"), hlc = Hlc(1, 2), deviceId = 1, updatedAt = now),
        SyncRecord(key = "huya:3", value = SyncCodec.stringValue("c"), hlc = Hlc(1, 3), deviceId = 1, updatedAt = now),
      ),
    )

    // Burn a few sequence numbers first, so a sequence-based count would be visibly wrong.
    engine.buildPayload(SyncScope.FOLLOWS)
    engine.buildPayload(SyncScope.FOLLOWS)

    val outcomes = SyncCoordinator(engine, FakeTransport()).syncWith(peers.first())
    val follows = outcomes.first { it.scope == SyncScope.FOLLOWS }

    assertEquals(3, follows.pushed, "expected the record count, got ${follows.pushed}")
  }

  @Test
  fun `a pulled payload is applied before the push happens`() = runTest {
    val target = Target(SyncScope.FOLLOWS)
    val peers = mutableListOf(
      TrustedPeer(deviceId = 2, deviceName = "tablet", sharedSecret = "s", pairedAt = 0),
    )
    val engine = engine(listOf(target), peers)
    val remote = SyncPayload(
      scope = "follows",
      sourceDeviceId = 2,
      sequence = 1,
      createdAt = now,
      entries = SyncCodec.encodeRecords(
        listOf(
          SyncRecord(
            key = "douyu:1",
            value = SyncCodec.stringValue("room"),
            hlc = Hlc(1, 0),
            deviceId = 2,
            updatedAt = 1,
          ),
        ),
      ),
    )
    val transport = FakeTransport(remotePayloads = mapOf(SyncScope.FOLLOWS to remote))

    val outcomes = SyncCoordinator(engine, transport).syncWith(peers.first())

    assertEquals(1, target.records.size)
    assertEquals(1, outcomes.first().pulled)
    assertTrue(outcomes.first().success)
  }

  @Test
  fun `a failing pull is reported per scope and does not stop other scopes`() = runTest {
    val follows = Target(SyncScope.FOLLOWS)
    val prefs = Target(SyncScope.PREFERENCES)
    val peers = mutableListOf(
      TrustedPeer(deviceId = 2, deviceName = "tablet", sharedSecret = "s", pairedAt = 0),
    )
    val engine = engine(listOf(follows, prefs), peers)
    val transport = FakeTransport(failOn = setOf(SyncScope.FOLLOWS))

    val outcomes = SyncCoordinator(engine, transport).syncWith(peers.first())

    assertEquals(2, outcomes.size)
    assertFalse(outcomes.first { it.scope == SyncScope.FOLLOWS }.success)
    assertTrue(outcomes.first { it.scope == SyncScope.PREFERENCES }.success)
  }

  @Test
  fun `syncAll visits every paired peer`() = runTest {
    val target = Target(SyncScope.FOLLOWS)
    val peers = mutableListOf(
      TrustedPeer(1, "tablet", "s", 0),
      TrustedPeer(2, "tv", "s", 0),
    )
    val engine = engine(listOf(target), peers)
    val transport = FakeTransport()

    val results = SyncCoordinator(engine, transport).syncAll()

    assertEquals(2, results.size)
    // One pull per peer; the push is skipped because an empty local scope has no delta.
    assertEquals(listOf("pull:follows", "pull:follows"), transport.calls)
  }

  /**
   * The push only carries what the peer is missing.
   *
   * The peer's pull response is proof of its contents, so a record both sides hold at the
   * same version is not sent again. Before this, a sync of two mostly-identical devices
   * moved the entire scope in both directions on every run.
   */
  @Test
  fun `only records the peer lacks are pushed`() = runTest {
    val target = Target(SyncScope.FOLLOWS)
    val peers = mutableListOf(
      TrustedPeer(deviceId = 2, deviceName = "tablet", sharedSecret = "s", pairedAt = 0),
    )
    val engine = engine(listOf(target), peers)
    val shared = SyncRecord(
      key = "douyu:1",
      value = SyncCodec.stringValue("common"),
      hlc = Hlc(1, 0),
      deviceId = 2,
      updatedAt = 1,
    )
    target.apply(
      listOf(
        shared,
        SyncRecord(key = "douyu:2", value = SyncCodec.stringValue("local-only"), hlc = Hlc(2, 0), deviceId = 1, updatedAt = 2),
      ),
    )
    val remote = SyncPayload(
      scope = "follows",
      sourceDeviceId = 2,
      sequence = 1,
      createdAt = now,
      entries = SyncCodec.encodeRecords(listOf(shared)),
    )
    val sent = mutableListOf<SyncPayload>()
    val transport = object : SyncTransport {
      override suspend fun pull(peer: TrustedPeer, scope: SyncScope) = remote
      override suspend fun push(peer: TrustedPeer, payload: SyncPayload): SyncPayload? {
        sent += payload
        return null
      }
      override suspend fun probe(peer: TrustedPeer) = true
    }

    SyncCoordinator(engine, transport).syncWith(peers.first())

    assertEquals(1, sent.size)
    val keys = SyncCodec.decodeRecords(sent.single().entries).map { it.key }
    assertEquals(listOf("douyu:2"), keys, "the shared record must not be echoed back")
  }

  /** A scope larger than the per-payload cap is split, and every part is applied. */
  @Test
  fun `a large scope is split into payloads under the cap`() = runTest {
    val target = Target(SyncScope.FOLLOWS)
    val peers = mutableListOf(
      TrustedPeer(deviceId = 2, deviceName = "tablet", sharedSecret = "s", pairedAt = 0),
    )
    val engine = engine(listOf(target), peers)
    target.apply(
      (1..12).map { index ->
        SyncRecord(
          key = "douyu:$index",
          value = SyncCodec.stringValue("r$index"),
          hlc = Hlc(index.toLong(), 0),
          deviceId = 1,
          updatedAt = index.toLong(),
        )
      },
    )

    val payloads = engine.buildPayloads(SyncScope.FOLLOWS, maxRecordsPerPayload = 5)

    assertEquals(3, payloads.size)
    assertEquals(listOf(5, 5, 2), payloads.map { it.recordCount })
    assertEquals(listOf(0, 1, 2), payloads.map { it.chunkIndex })
    assertTrue(payloads.all { it.chunkCount == 3 })
    assertTrue(payloads[0].hasMoreChunks, "the first of three parts still has more to follow")
    assertFalse(payloads.last().hasMoreChunks, "the final part must be marked as last")
    // Every part gets its own sequence so the peer's de-duplication guard works per part.
    assertEquals(3, payloads.map { it.sequence }.toSet().size)
  }

  /** The default cap is used when the caller does not ask for a specific one. */
  @Test
  fun `a scope under the default cap stays a single payload`() = runTest {
    val target = Target(SyncScope.FOLLOWS)
    val peers = mutableListOf(
      TrustedPeer(deviceId = 2, deviceName = "tablet", sharedSecret = "s", pairedAt = 0),
    )
    val engine = engine(listOf(target), peers)
    target.apply(
      listOf(SyncRecord(key = "a", value = SyncCodec.stringValue("1"), hlc = Hlc(1, 0), deviceId = 1, updatedAt = 1)),
    )

    val payloads = engine.buildPayloads(SyncScope.FOLLOWS)

    assertEquals(1, payloads.size)
    assertEquals(1, payloads.single().chunkCount)
    assertEquals(1, payloads.single().recordCount)
  }

  /** A disabled scope produces no payloads at all, not an empty one. */
  @Test
  fun `a disabled scope produces no payloads`() = runTest {
    val engine = SyncEngine(
      targets = MapSyncTargets(emptyMap()),
      deviceIdProvider = { 1 },
      deviceNameProvider = { "phone" },
      peersProvider = { emptyList() },
      peersWriter = {},
      clock = { now },
    )

    assertTrue(engine.buildPayloads(SyncScope.FOLLOWS).isEmpty())
    assertNull(engine.buildPayload(SyncScope.FOLLOWS))
  }

  @Test
  fun `the sync log records both directions`() = runTest {
    val target = Target(SyncScope.FOLLOWS)
    val peers = mutableListOf(
      TrustedPeer(deviceId = 2, deviceName = "tablet", sharedSecret = "s", pairedAt = 0),
    )
    val engine = engine(listOf(target), peers)
    val remote = SyncPayload(
      scope = "follows",
      sourceDeviceId = 2,
      sequence = 1,
      createdAt = now,
      entries = SyncCodec.encodeRecords(
        listOf(
          SyncRecord(key = "k", value = SyncCodec.stringValue("v"), hlc = Hlc(1, 0), deviceId = 2, updatedAt = 1),
        ),
      ),
    )

    SyncCoordinator(engine, FakeTransport(mapOf(SyncScope.FOLLOWS to remote))).syncWith(peers.first())

    val log = engine.logEntries()
    assertTrue(log.any { it.direction == SyncLogEntry.Direction.PULL })
    assertTrue(log.any { it.direction == SyncLogEntry.Direction.PUSH })
  }
}

class SyncCodecTest {

  @Test
  fun `records round trip through JSON`() {
    val records: List<SyncRecord<kotlinx.serialization.json.JsonObject>> = listOf(
      SyncRecord(
        key = "douyu:1",
        value = SyncCodec.stringValue("主播"),
        deleted = false,
        hlc = Hlc(123, 4),
        deviceId = 9,
        updatedAt = 123,
      ),
      SyncRecord(key = "huya:2", value = null, deleted = true, hlc = Hlc(200, 0), deviceId = 9, updatedAt = 200),
    )

    val decoded = SyncCodec.decodeRecords(SyncCodec.encodeRecords(records))

    assertEquals(2, decoded.size)
    assertEquals("douyu:1", decoded[0].key)
    assertEquals("主播", SyncCodec.readString(decoded[0].value))
    assertTrue(decoded[1].deleted)
    assertEquals(Hlc(200, 0), decoded[1].hlc)
  }

  @Test
  fun `decoding an empty payload yields no records`() {
    assertTrue(SyncCodec.decodeRecords("").isEmpty())
  }

  @Test
  fun `malformed entries are skipped rather than throwing`() {
    val decoded = SyncCodec.decodeRecords("""[{"noKey":true},{"key":"ok","deleted":false,"hlc":"1-0"}]""")
    assertEquals(1, decoded.size)
    assertEquals("ok", decoded.first().key)
  }

  @Test
  fun `payload round trips`() {
    val payload = SyncCodec.buildPayload(
      scope = SyncScope.FOLLOWS,
      records = listOf(
        SyncRecord(key = "a", value = SyncCodec.intValue(1), hlc = Hlc(1, 0), deviceId = 1, updatedAt = 1),
      ),
      deviceId = 1,
      sequence = 7,
      now = 1,
    )
    val decoded = SyncCodec.decodePayload(SyncCodec.encodePayload(payload))

    assertEquals(SyncScope.FOLLOWS, decoded.parsedScope)
    assertEquals(7, decoded.sequence)
    assertEquals(1, SyncCodec.decodeRecords(decoded.entries).size)
  }

  @Test
  fun `typed value helpers round trip every supported shape`() {
    assertEquals("x", SyncCodec.readString(SyncCodec.stringValue("x")))
    assertEquals(3, SyncCodec.readInt(SyncCodec.intValue(3)))
    assertEquals(9L, SyncCodec.readLong(SyncCodec.longValue(9)))
    assertEquals(true, SyncCodec.readFlag(SyncCodec.flagValue(true)))
    assertEquals(listOf("a", "b"), SyncCodec.readStringList(SyncCodec.stringListValue(listOf("a", "b"))))
    assertEquals(null, SyncCodec.readString(null))
  }
}
