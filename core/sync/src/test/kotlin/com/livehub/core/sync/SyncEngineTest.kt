package com.livehub.core.sync

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** In-memory [SyncTarget] used to exercise the engine without Android dependencies. */
private class FakeTarget(override val scope: SyncScope) : SyncTarget {
  var records: List<SyncRecord<JsonObject>> = emptyList()
    private set

  override fun snapshot(): List<SyncRecord<out Any>> = records

  override fun apply(records: List<SyncRecord<out Any>>): Int {
    @Suppress("UNCHECKED_CAST")
    val typed = records as List<SyncRecord<JsonObject>>
    val before = this.records.associate { it.key to it.version }
    this.records = typed
    return typed.count { before[it.key] != it.version }
  }

  fun put(record: SyncRecord<JsonObject>) {
    records = records.filterNot { it.key == record.key } + record
  }
}

class SyncEngineTest {

  private var now = 1_000L

  private fun engine(
    targets: List<FakeTarget>,
    peers: MutableList<TrustedPeer> = mutableListOf(),
    deviceId: Long = 1L,
  ): SyncEngine = SyncEngine(
    targets = MapSyncTargets(targets.associateBy { it.scope }),
    deviceIdProvider = { deviceId },
    deviceNameProvider = { "device-$deviceId" },
    peersProvider = { peers.toList() },
    peersWriter = { peers.clear(); peers.addAll(it) },
    clock = { now },
    idGenerator = { "secret-token" },
  )

  private fun alive(key: String, hlc: Hlc, device: Long, value: String = key): SyncRecord<JsonObject> =
    SyncRecord(
      key = key,
      value = SyncCodec.stringValue(value),
      deleted = false,
      hlc = hlc,
      deviceId = device,
      updatedAt = hlc.physical,
    )

  @Test
  fun `payload round trips through the wire format`() {
    val target = FakeTarget(SyncScope.FOLLOWS)
    target.put(alive("douyu:1", Hlc(5, 0), device = 1, value = "主播"))
    val engine = engine(listOf(target))

    val payload = engine.buildPayload(SyncScope.FOLLOWS)
    assertNotNull(payload)
    payload!!

    val encoded = SyncCodec.encodePayload(payload)
    val decoded = SyncCodec.decodePayload(encoded)

    assertEquals(SyncScope.FOLLOWS, decoded.parsedScope)
    assertEquals(1, decoded.sourceDeviceId)
    val records = SyncCodec.decodeRecords(decoded.entries)
    assertEquals(1, records.size)
    assertEquals("主播", SyncCodec.readString(records.first().value))
  }

  @Test
  fun `applying a payload merges into the local target`() {
    val target = FakeTarget(SyncScope.FOLLOWS)
    val engine = engine(listOf(target))
    val peer = TrustedPeer(2, "tablet", "s", 0)

    val incoming = SyncPayload(
      scope = "follows",
      sourceDeviceId = 2,
      sequence = 1,
      createdAt = now,
      entries = SyncCodec.encodeRecords(listOf(alive("huya:9", Hlc(7, 0), device = 2))),
    )

    val result = engine.applyPayload(incoming, peer)

    assertTrue(result.accepted)
    assertEquals(1, result.applied)
    assertEquals(1, target.records.size)
    assertEquals("huya:9", target.records.first().key)
  }

  /**
   * A payload that was already applied is a no-op, not an error.
   *
   * The merge is idempotent, so a retry cannot duplicate anything — that part was always
   * true. What was wrong was reporting it as a rejection: `SyncCoordinator` treats a rejected
   * pull as a failed scope and stops, so the push half of the exchange never ran and a peer
   * that merely repeated itself left the two devices out of step.
   */
  @Test
  fun `a replayed payload is applied as a no-op`() {
    val target = FakeTarget(SyncScope.FOLLOWS)
    val peers = mutableListOf(TrustedPeer(2, "tablet", "s", 0))
    val engineWithPeer = engine(listOf(target), peers)

    val duplicate = SyncPayload(
      scope = "follows",
      sourceDeviceId = 2,
      sequence = 5,
      createdAt = now,
      entries = SyncCodec.encodeRecords(listOf(alive("a", Hlc(1, 0), device = 2))),
    )

    val result = engineWithPeer.applyPayload(duplicate, peers.first().withApplied("follows", 5, now))

    assertTrue(result.accepted, "a replay must not fail the exchange")
    assertEquals(0, result.applied)
    assertTrue(target.records.isEmpty(), "nothing may reach the store twice")
    // ...and the log still says what happened, instead of looking like an empty success.
    assertEquals("该版本已应用，跳过", engineWithPeer.logEntries().first().note)
  }

  /**
   * A sender whose process restarted must still be accepted.
   *
   * The watermark lives in [TrustedPeer] (persisted), the sender's counter lives in the
   * engine (not persisted). When the counter started at 0 on every launch, a restarted device
   * sent payloads numbered below the peer's watermark and every one of them was refused:
   * sync did nothing at all, one tap at a time, until the counter climbed back past it.
   */
  @Test
  fun `a payload from a restarted sender still reaches the receiver`() {
    val receiverPeers = mutableListOf(TrustedPeer(2, "tablet", "s", 0))
    val receiver = engine(listOf(FakeTarget(SyncScope.FOLLOWS)), receiverPeers, deviceId = 1)

    val firstTarget = FakeTarget(SyncScope.FOLLOWS)
    firstTarget.put(alive("douyu:1", Hlc(5, 0), device = 2))
    val firstProcess = engine(listOf(firstTarget), deviceId = 2)
    val first = firstProcess.buildPayload(SyncScope.FOLLOWS)!!
    assertTrue(receiver.applyPayload(first, receiverPeers.first()).accepted)

    // The other device was closed and reopened: a new engine, with no memory of the old
    // counter, and one more room followed after the restart.
    now += 60_000
    val secondTarget = FakeTarget(SyncScope.FOLLOWS)
    secondTarget.put(alive("douyu:1", Hlc(5, 0), device = 2))
    secondTarget.put(alive("douyu:2", Hlc(6, 0), device = 2))
    val secondProcess = engine(listOf(secondTarget), deviceId = 2)
    val second = secondProcess.buildPayload(SyncScope.FOLLOWS)!!

    val result = receiver.applyPayload(second, receiverPeers.first())

    // The watermark the receiver stored before the restart, for the failure message only.
    val watermark = receiverPeers.first().appliedSequences["follows"]
    assertTrue(
      result.accepted,
      "the restarted sender's payload (${second.sequence}) was refused against a watermark of $watermark",
    )
    assertEquals(1, result.applied, "the room followed after the restart must arrive")
    assertEquals(second.sequence, receiverPeers.first().appliedSequences["follows"])
  }

  @Test
  fun `a payload from a newer schema is rejected`() {
    val target = FakeTarget(SyncScope.FOLLOWS)
    val engine = engine(listOf(target))

    val future = SyncPayload(
      scope = "follows",
      schema = SyncPayload.SCHEMA + 1,
      sourceDeviceId = 2,
      sequence = 1,
      createdAt = now,
      entries = "[]",
    )

    val result = engine.applyPayload(future, null)
    assertFalse(result.accepted)
    assertTrue(result.rejectedReason!!.contains("版本过新"))
  }

  @Test
  fun `an unknown scope is rejected without throwing`() {
    val engine = engine(listOf(FakeTarget(SyncScope.FOLLOWS)))
    val weird = SyncPayload(
      scope = "unknown-scope",
      sourceDeviceId = 2,
      sequence = 1,
      createdAt = now,
      entries = "[]",
    )

    val result = engine.applyPayload(weird, null)
    assertFalse(result.accepted)
    assertTrue(result.rejectedReason!!.contains("未知"))
  }

  @Test
  fun `applying a payload marks the scope as applied for that peer`() {
    val peers = mutableListOf(TrustedPeer(2, "tablet", "s", 0))
    val target = FakeTarget(SyncScope.FOLLOWS)
    val engine = engine(listOf(target), peers)

    val payload = SyncPayload(
      scope = "follows",
      sourceDeviceId = 2,
      sequence = 3,
      createdAt = now,
      entries = SyncCodec.encodeRecords(listOf(alive("a", Hlc(2, 0), device = 2))),
    )
    engine.applyPayload(payload, peers.first())

    assertEquals(3L, peers.first().appliedSequences["follows"])
  }

  /**
   * The enabled set comes from the target registry, not from the scope enum.
   *
   * This used to be pinned through `buildAllPayloads`, a method only this test called; deleting
   * it (dead production API) leaves the property to be asserted directly.
   */
  @Test
  fun `enabledScopes follows the target registry`() {
    val follows = FakeTarget(SyncScope.FOLLOWS)
    fun engineFor(enabled: Set<SyncScope>) = SyncEngine(
      targets = MapSyncTargets(mapOf(SyncScope.FOLLOWS to follows), enabled = enabled),
      deviceIdProvider = { 1 },
      deviceNameProvider = { "phone" },
      peersProvider = { emptyList() },
      peersWriter = {},
      clock = { now },
    )

    assertTrue(engineFor(emptySet()).enabledScopes().isEmpty())
    assertEquals(setOf(SyncScope.FOLLOWS), engineFor(setOf(SyncScope.FOLLOWS)).enabledScopes())
  }

  @Test
  fun `linking a peer stores the secret it presented`() {
    val peers = mutableListOf<TrustedPeer>()
    val engine = engine(listOf(FakeTarget(SyncScope.FOLLOWS)), peers)

    val result = engine.linkPeer(
      peerDeviceId = 42,
      peerDeviceName = "tablet",
      remoteSecret = "remote-secret",
    )

    assertTrue(result.isSuccess)
    assertEquals(1, peers.size)
    assertEquals(42L, peers.first().deviceId)
    // The peer's own secret is what it will present, so that is what has to be stored.
    assertEquals("remote-secret", peers.first().sharedSecret)
  }

  @Test
  fun `linking without a remote secret generates one and strips dashes`() {
    val peers = mutableListOf<TrustedPeer>()
    val engine = engine(listOf(FakeTarget(SyncScope.FOLLOWS)), peers)

    val result = engine.linkPeer(peerDeviceId = 42, peerDeviceName = "tablet")

    assertTrue(result.isSuccess)
    // The engine strips the dashes so the secret is URL/QR friendly.
    assertEquals("secrettoken", peers.first().sharedSecret)
  }

  @Test
  fun `unpair removes only the requested peer`() {
    val peers = mutableListOf(
      TrustedPeer(2, "tablet", "s", 0),
      TrustedPeer(3, "tv", "s", 0),
    )
    val engine = engine(listOf(FakeTarget(SyncScope.FOLLOWS)), peers)

    engine.unpair(2)

    assertEquals(1, peers.size)
    assertEquals(3L, peers.first().deviceId)
  }

  @Test
  fun `push and pull entries appear in the log`() {
    val peers = mutableListOf(TrustedPeer(2, "tablet", "s", 0))
    val target = FakeTarget(SyncScope.FOLLOWS)
    val engine = engine(listOf(target), peers)

    engine.recordPush(peers.first(), SyncScope.FOLLOWS, itemCount = 3, success = true)
    val pull = SyncPayload(
      scope = "follows",
      sourceDeviceId = 2,
      sequence = 1,
      createdAt = now,
      entries = SyncCodec.encodeRecords(listOf(alive("a", Hlc(1, 0), device = 2))),
    )
    engine.applyPayload(pull, peers.first())

    val entries = engine.logEntries()
    assertEquals(2, entries.size)
    assertEquals(SyncLogEntry.Direction.PULL, entries.first().direction)
    assertEquals(SyncLogEntry.Direction.PUSH, entries.last().direction)
  }

  @Test
  fun `sequence numbers increase so peers can deduplicate`() {
    val engine = engine(listOf(FakeTarget(SyncScope.FOLLOWS)))
    val first = engine.buildPayload(SyncScope.FOLLOWS)!!
    val second = engine.buildPayload(SyncScope.FOLLOWS)!!
    assertTrue(second.sequence > first.sequence)
  }

  @Test
  fun `missing target returns null instead of throwing`() {
    val engine = SyncEngine(
      targets = MapSyncTargets(emptyMap()),
      deviceIdProvider = { 1 },
      deviceNameProvider = { "phone" },
      peersProvider = { emptyList() },
      peersWriter = {},
      clock = { now },
    )
    assertNull(engine.buildPayload(SyncScope.FOLLOWS))
  }
}
