package com.livehub.core.sync

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    var records: List<SyncRecord<Any>> = emptyList()
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
    val transport = FakeTransport()

    SyncCoordinator(engine, transport).syncWith(peers.first())

    assertEquals(listOf("pull:follows", "push:follows"), transport.calls)
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
    assertEquals(4, transport.calls.size)
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
    val records = listOf(
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
