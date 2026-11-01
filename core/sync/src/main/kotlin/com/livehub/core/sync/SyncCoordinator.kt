package com.livehub.core.sync

/**
 * Drives a full two-way exchange with a peer.
 *
 * The flow mirrors WeChat's cross-device sync semantics rather than a naive "upload and
 * overwrite":
 *
 * 1. **Ping** — verify the peer is reachable and speaks the same protocol version.
 * 2. **Pull** — fetch the peer's payload for each scope and merge it locally.
 * 3. **Push** — send our merged payload back so the peer converges too.
 *
 * Every step is idempotent: payloads carry `(deviceId, sequence)` so a retried request
 * is recognised and skipped instead of applied twice.
 */
class SyncCoordinator(
  private val engine: SyncEngine,
  private val transport: SyncTransport,
) {
  /** Result of one scope exchange. */
  data class ScopeOutcome(
    val scope: SyncScope,
    val pulled: Int,
    val pushed: Int,
    val error: String? = null,
  ) {
    val success: Boolean get() = error == null
  }

  /** Runs the full exchange against every paired peer. */
  suspend fun syncAll(): List<Pair<TrustedPeer, List<ScopeOutcome>>> =
    engine.peers().map { peer -> peer to syncWith(peer) }

  /** Runs the exchange with one peer. */
  suspend fun syncWith(peer: TrustedPeer): List<ScopeOutcome> {
    val outcomes = mutableListOf<ScopeOutcome>()

    for (scope in engine.enabledScopes()) {
      val remote = try {
        transport.pull(peer, scope)
      } catch (error: Exception) {
        outcomes += ScopeOutcome(scope, pulled = 0, pushed = 0, error = error.message ?: "拉取失败")
        engine.recordPush(peer, scope, itemCount = 0, success = false, note = error.message)
        continue
      }

      var pulled = 0
      if (remote != null) {
        val applied = engine.applyPayload(remote, peer)
        pulled = applied.added
        if (!applied.accepted) {
          outcomes += ScopeOutcome(scope, pulled = 0, pushed = 0, error = applied.rejectedReason)
          continue
        }
      }

      // The pull above told us exactly what the peer holds, so only the records it is
      // missing (or where our version wins the merge) have to travel back. Before this,
      // every sync re-sent the whole scope in both directions regardless of the delta.
      val peerKnown = remote?.let { SyncCodec.decodeRecords(it.entries) }.orEmpty()
      val payloads = engine.buildPayloads(scope, peerKnown)

      var pushed = 0
      var pushError: String? = null
      for (payload in payloads) {
        if (payload.recordCount == 0) {
          // Nothing to send: skip the round trip instead of pushing an empty document.
          continue
        }
        val response = try {
          transport.push(peer, payload)
        } catch (error: Exception) {
          pushError = error.message ?: "推送失败"
          engine.recordPush(peer, scope, itemCount = pushed, success = false, note = pushError)
          break
        }
        if (response != null) {
          val applied = engine.applyPayload(response, peer)
          pulled += applied.added
        }
        pushed += payload.recordCount
      }

      if (pushError != null) {
        outcomes += ScopeOutcome(scope, pulled = pulled, pushed = pushed, error = pushError)
        continue
      }

      // The record count, not the payload sequence. `sequence` is a counter that only ever
      // grows, so showing it as "sent N items" made the sync summary report numbers
      // unrelated to what was transferred — and the de-duplication guard means the same
      // sequence is never re-sent anyway.
      engine.recordPush(
        peer = peer,
        scope = scope,
        itemCount = pushed,
        success = true,
        note = if (pushed == 0) "无需发送" else "发送 $pushed 条",
      )
      outcomes += ScopeOutcome(scope, pulled = pulled, pushed = pushed)
    }

    return outcomes
  }

  /** Checks reachability, used to grey out offline peers in the UI. */
  suspend fun ping(peer: TrustedPeer): Boolean = transport.probe(peer)
}
