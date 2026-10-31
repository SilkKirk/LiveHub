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

      val local = engine.buildPayload(scope)
      var pushed = 0
      if (local != null) {
        val response = try {
          transport.push(peer, local)
        } catch (error: Exception) {
          outcomes += ScopeOutcome(scope, pulled = pulled, pushed = 0, error = error.message ?: "推送失败")
          engine.recordPush(peer, scope, itemCount = 0, success = false, note = error.message)
          continue
        }
        if (response != null) {
          val applied = engine.applyPayload(response, peer)
          pulled += applied.added
        }
        pushed = local.sequence.toInt().coerceAtLeast(0)
        engine.recordPush(peer, scope, itemCount = pushed, success = true)
      }

      outcomes += ScopeOutcome(scope, pulled = pulled, pushed = pushed)
    }

    return outcomes
  }

  /** Checks reachability, used to grey out offline peers in the UI. */
  suspend fun ping(peer: TrustedPeer): Boolean = transport.probe(peer)
}
