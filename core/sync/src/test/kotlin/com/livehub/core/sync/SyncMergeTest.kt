package com.livehub.core.sync

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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

  @Test
  fun `tombstones are pruned once older than the retention window`() {
    val records = listOf(tombstone("a", Hlc(1_000, 0)))
    val peers = listOf(
      TrustedPeer(deviceId = 2, deviceName = "peer", sharedSecret = "s", pairedAt = 0),
    )
    val now = 1_000 + SyncMerge.TOMBSTONE_RETENTION_MILLIS + 1

    assertTrue(SyncMerge.prune(records, peers, now).isEmpty())
    assertNotNull(SyncMerge.prune(records, peers, 1_000).firstOrNull())
  }

  @Test
  fun `pruning keeps live records and is skipped when no peers exist`() {
    val records = listOf(alive("a", Hlc(1, 0)), tombstone("b", Hlc(1, 0)))
    assertEquals(2, SyncMerge.prune(records, emptyList(), Long.MAX_VALUE / 2).size)
    assertEquals(1, SyncMerge.prune(records, listOf(TrustedPeer(2, "p", "s", 0)), Long.MAX_VALUE / 2).size)
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
