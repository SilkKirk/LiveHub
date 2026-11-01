package com.livehub.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Envelope for every HTTP exchange between two LiveHub installs.
 *
 * Requests carry the pairing secret so a peer can reject unpaired traffic, and an
 * explicit [protocolVersion] so a future incompatible change can fail fast instead of
 * corrupting state.
 */
@Serializable
data class SyncEnvelope(
  val protocolVersion: Int = PROTOCOL_VERSION,
  val deviceId: Long,
  val deviceName: String,
  val secret: String,
  /** Echoed by the responder so the caller can detect a version mismatch. */
  val serverDeviceId: Long = 0,
  val serverDeviceName: String = "",
  /**
   * Port the sender's own server is listening on.
   *
   * The LAN server binds an ephemeral port when the preferred one is taken, and the
   * peer cannot guess it. Carrying it in the handshake is what lets the other side
   * record a reachable address instead of assuming the default.
   */
  val preferredPort: Int = 0,
  /** Set on error responses. */
  val error: String? = null,
  /** The payload being pushed or returned. */
  val payload: SyncPayload? = null,
) {
  companion object {
    const val PROTOCOL_VERSION = 1
    const val PATH_PING = "/livehub/v1/ping"
    const val PATH_PULL = "/livehub/v1/pull"
    const val PATH_PUSH = "/livehub/v1/push"
    const val PATH_PAIR = "/livehub/v1/pair"

    val json: Json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      explicitNulls = false
    }

    fun encode(envelope: SyncEnvelope): String = json.encodeToString(serializer(), envelope)

    fun decode(raw: String): SyncEnvelope = json.decodeFromString(serializer(), raw)
  }
}

/**
 * One request/response round trip over LAN.
 *
 * The transport is intentionally a single method: everything else (pairing, pulling,
 * pushing) is expressed as a path plus an envelope, so the server side is one small
 * router and tests can drive it with a fake.
 */
interface SyncHttpTransport {
  suspend fun call(
    peer: TrustedPeer,
    host: String,
    port: Int,
    path: String,
    envelope: SyncEnvelope,
  ): SyncEnvelope
}

/** Error returned by a peer that rejected the request. */
class SyncRejectedException(
  val reason: String,
  val statusCode: Int = 0,
) : Exception(reason)

/** Reasons a peer can be unreachable, surfaced verbatim in the sync UI. */
enum class SyncFailureReason { TIMEOUT, REFUSED, UNAUTHORIZED, VERSION_MISMATCH, PROTOCOL, UNKNOWN }

/** Wraps a transport failure with a user presentable reason. */
class SyncTransportException(
  val reason: SyncFailureReason,
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)
