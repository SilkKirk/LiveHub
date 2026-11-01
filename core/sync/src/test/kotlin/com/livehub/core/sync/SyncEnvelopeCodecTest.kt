package com.livehub.core.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Focused checks on the wire format.
 *
 * The end-to-end test surfaced a mismatch between what the pair handler returns and what
 * the caller reads back, so the envelope round trip is pinned down here first.
 */
class SyncEnvelopeCodecTest {

  @Test
  fun `every field survives a round trip`() {
    val original = SyncEnvelope(
      protocolVersion = SyncEnvelope.PROTOCOL_VERSION,
      deviceId = 2,
      deviceName = "Tablet",
      secret = "issued",
      serverDeviceId = 2,
      serverDeviceName = "Tablet",
      preferredPort = 47653,
      error = null,
      payload = SyncPayload(
        scope = "follows",
        sourceDeviceId = 2,
        sequence = 7,
        createdAt = 1,
        entries = "[]",
      ),
    )

    val decoded = SyncEnvelope.decode(SyncEnvelope.encode(original))

    assertEquals(2L, decoded.deviceId)
    assertEquals("Tablet", decoded.deviceName)
    assertEquals("issued", decoded.secret)
    assertEquals(2L, decoded.serverDeviceId)
    assertEquals("Tablet", decoded.serverDeviceName)
    assertEquals(47653, decoded.preferredPort)
    assertEquals(7L, decoded.payload?.sequence)
  }

  @Test
  fun `serverDeviceId is encoded even when it equals deviceId`() {
    val encoded = SyncEnvelope.encode(
      SyncEnvelope(deviceId = 2, deviceName = "T", secret = "", serverDeviceId = 2),
    )
    // encodeDefaults = true is what makes the responder identity visible to the caller.
    assertTrue(encoded.contains("\"serverDeviceId\":2"), "payload was $encoded")
  }

  @Test
  fun `preferredPort is encoded`() {
    val encoded = SyncEnvelope.encode(
      SyncEnvelope(deviceId = 1, deviceName = "P", secret = "", preferredPort = 12345),
    )
    assertTrue(encoded.contains("\"preferredPort\":12345"), "payload was $encoded")
  }

  @Test
  fun `a payload from an older peer without the new fields still decodes`() {
    val legacy = """{"deviceId":1,"deviceName":"Old","secret":"s"}"""
    val decoded = SyncEnvelope.decode(legacy)

    assertEquals(1L, decoded.deviceId)
    assertEquals(0, decoded.preferredPort)
    assertEquals(SyncEnvelope.PROTOCOL_VERSION, decoded.protocolVersion)
  }
}
