package com.livehub.core.sync

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncMergeTest {
  private fun alive(key: String, hlc: Hlc, device: Long = 1L, value: String = key): SyncRecord<JsonObject> =
    SyncRecord(
      key = key,
      value = SyncCodec.stringValue(value),
      deleted = false,
      hlc = hlc,
      deviceId = device,
      updatedAt = hlc.physical,
    )

  private fun tombstone(key: String, hlc: Hlc, device: Long = 1L): SyncRecord<JsonObject> =
    SyncRecord(
      key = key,
      value = null,
      deleted = true,
      hlc = hlc,
      deviceId = device,
      updatedAt = hlc.physical,
    )

  @Test
  fun `records only known to the remote are added`() {
    val local = listOf(alive("a", Hlc(10, 0)))
    val remote = listOf(alive("b", Hlc(11, 0)))

    val outcome = SyncMerge.merge(local, remote)

    assertEquals(2, outcome.merged.size)
    assertEquals(1, outcome.localWins)
    assertEquals(1, outcome.remoteWins)
    assertTrue(outcome.changed)
  }

  @Test
  fun `higher hybrid logical clock wins`() {
    val local = listOf(alive("a", Hlc(10, 0), value = "local"))
    val remote = listOf(alive("a", Hlc(20, 0), value = "remote"))

    val outcome = SyncMerge.merge(local, remote)

    assertEquals(1, outcome.merged.size)
    assertEquals("remote", SyncCodec.readString(outcome.merged.first().value))
    assertEquals(1, outcome.remoteWins)
  }

  @Test
  fun `lower remote clock does not overwrite the local value`() {
    val local = listOf(alive("a", Hlc(30, 0), value = "local"))
    val remote = listOf(alive("a", Hlc(20, 0), value = "remote"))

    val outcome = SyncMerge.merge(local, remote)

    assertEquals("local", SyncCodec.readString(outcome.merged.first().value))
    assertEquals(1, outcome.localWins)
    assertEquals(0, outcome.remoteWins)
    assertFalse(outcome.changed)
  }

  @Test
  fun `device id breaks clock ties deterministically`() {
    val local = listOf(alive("a", Hlc(10, 0), device = 5, value = "local"))
    val remote = listOf(alive("a", Hlc(10, 0), device = 9, value = "remote"))

    assertEquals(
      "remote",
      SyncCodec.readString(SyncMerge.merge(local, remote).merged.first().value),
    )
    assertEquals(
      "remote",
      SyncCodec.readString(SyncMerge.merge(remote, local).merged.first().value),
    )
  }

  @Test
  fun `tombstones propagate and win over stale live records`() {
    val local = listOf(alive("a", Hlc(10, 0), value = "kept"))
    val remote = listOf(tombstone("a", Hlc(20, 0)))

    val outcome = SyncMerge.merge(local, remote)

    assertTrue(outcome.merged.first().deleted)
    assertNull(outcome.merged.first().value)
  }

  @Test
  fun `an older live record cannot resurrect a tombstone`() {
    val local = listOf(tombstone("a", Hlc(30, 0)))
    val remote = listOf(alive("a", Hlc(20, 0)))

    val outcome = SyncMerge.merge(local, remote)

    assertTrue(outcome.merged.first().deleted)
    assertEquals(0, outcome.remoteWins)
  }

  @Test
  fun `identical versions are counted but not applied`() {
    val record = alive("a", Hlc(10, 0), device = 3)
    val outcome = SyncMerge.merge(listOf(record), listOf(record))

    assertEquals(1, outcome.identical)
    assertEquals(0, outcome.remoteWins)
    assertFalse(outcome.changed)
  }

  /**
   * The delta filter must never drop a record the merge would have taken.
   *
   * Sending only the delta is safe exactly when "skipped by the sender" implies "the
   * receiver's copy wins the merge", so this pins that equivalence: for a record the
   * sender omits, merging the sender's copy into the receiver must leave it unchanged.
   */
  @Test
  fun `a record skipped by the delta rule would lose the merge anyway`() {
    // Same clock, different devices: the higher device id decides, which is the tie break
    // the delta filter and the merge both use.
    val senderVersion = alive("a", Hlc(10, 0), device = 1)
    val receiverVersion = alive("a", Hlc(10, 0), device = 2)

    assertTrue(senderVersion.version < receiverVersion.version)

    val outcome = SyncMerge.merge(listOf(receiverVersion), listOf(senderVersion))
    assertEquals(receiverVersion.value, outcome.merged.single().value)
    assertEquals(0, outcome.remoteWins, "the skipped record must not win on arrival")
    assertEquals(1, outcome.localWins)
  }

  /** A tombstone still wins on a newer clock, which is why deletes are not filtered out. */
  @Test
  fun `a newer tombstone is not filtered out by the version comparison`() {
    val liveOnReceiver = alive("a", Hlc(10, 0), device = 2)
    val deletedOnSender = tombstone("a", Hlc(20, 0), device = 1)

    assertTrue(deletedOnSender.version > liveOnReceiver.version)

    val outcome = SyncMerge.merge(listOf(liveOnReceiver), listOf(deletedOnSender))
    assertTrue(outcome.merged.single().deleted, "the newer tombstone must win")
  }

  // --------------------------------------------------------------------- prune ---

  private fun peer(id: Long, confirmed: Hlc? = null): TrustedPeer {
    val base = TrustedPeer(deviceId = id, deviceName = "peer$id", sharedSecret = "s", pairedAt = 0)
    return if (confirmed == null) base else base.withConfirmed(SyncScope.FOLLOWS, confirmed)
  }

  /** Once every peer holds the delete, the tombstone has done its job. */
  @Test
  fun `a tombstone confirmed by every peer is dropped`() {
    val records = listOf(alive("a", Hlc(10, 0)), tombstone("b", Hlc(20, 0)))
    val peers = listOf(peer(2, Hlc(20, 0)), peer(3, Hlc(25, 0)))

    val pruned = SyncMerge.prune(records, peers, SyncScope.FOLLOWS)

    assertEquals(listOf("a"), pruned.map { it.key })
  }

  /**
   * One peer behind the tombstone is enough to keep it.
   *
   * That peer still holds the live record; dropping the tombstone now would let its copy win the
   * next merge and bring the unfollowed room back.
   */
  @Test
  fun `a tombstone one peer has not confirmed is kept`() {
    val records = listOf(tombstone("b", Hlc(20, 0)))
    val peers = listOf(peer(2, Hlc(20, 0)), peer(3, Hlc(19, 0)))

    assertEquals(1, SyncMerge.prune(records, peers, SyncScope.FOLLOWS).size)
  }

  /** Live records are never pruned, whatever the watermarks say. */
  @Test
  fun `live records survive pruning`() {
    val records = listOf(alive("a", Hlc(5, 0)), alive("b", Hlc(30, 0)))

    assertEquals(2, SyncMerge.prune(records, listOf(peer(2, Hlc(40, 0))), SyncScope.FOLLOWS).size)
  }

  /**
   * With no peers nothing can be confirmed, so nothing is dropped.
   *
   * A device that pairs later would otherwise resurrect every delete made before the pairing.
   */
  @Test
  fun `nothing is pruned without paired peers`() {
    val records = listOf(tombstone("b", Hlc(20, 0)))

    assertEquals(1, SyncMerge.prune(records, emptyList(), SyncScope.FOLLOWS).size)
  }

  /** A watermark for a different scope says nothing about this one. */
  @Test
  fun `a watermark only counts for its own scope`() {
    val records = listOf(tombstone("b", Hlc(20, 0)))
    val otherScope = peer(2).copy(confirmedHlc = mapOf("preferences" to "999-0"))

    assertEquals(1, SyncMerge.prune(records, listOf(otherScope), SyncScope.FOLLOWS).size)
  }
}

class HlcTest {

  @Test
  fun `tick advances the counter within the same millisecond`() {
    val first = Hlc.ZERO.tick(1_000)
    val second = first.tick(1_000)

    assertEquals(Hlc(1_000, 0), first)
    assertEquals(Hlc(1_000, 1), second)
    assertTrue(second > first)
  }

  @Test
  fun `tick jumps forward when the wall clock advances`() {
    val state = Hlc(1_000, 5)
    assertEquals(Hlc(2_000, 0), state.tick(2_000))
  }

  @Test
  fun `merge always produces a value greater than both inputs`() {
    val local = Hlc(1_000, 0)
    val remote = Hlc(900, 3)
    val merged = local.merge(remote, nowMillis = 950)

    assertTrue(merged > local)
    assertTrue(merged > remote)
  }

  @Test
  fun `merge adopts the newer physical clock`() {
    val merged = Hlc(1_000, 0).merge(Hlc(2_000, 4), nowMillis = 1_500)
    assertEquals(2_000L, merged.physical)
    assertEquals(5L, merged.counter)
  }

  @Test
  fun `encode and decode round trip`() {
    val hlc = Hlc(123_456_789, 7)
    assertEquals(hlc, Hlc.decode(hlc.encode()))
  }

  @Test
  fun `decode tolerates malformed input`() {
    assertEquals(Hlc.ZERO, Hlc.decode(null))
    assertEquals(Hlc.ZERO, Hlc.decode(""))
    assertEquals(Hlc.ZERO, Hlc.decode("garbage"))
    assertEquals(Hlc.ZERO, Hlc.decode("1-2-3"))
  }
}
