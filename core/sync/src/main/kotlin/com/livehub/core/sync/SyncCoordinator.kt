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
    /**
     * Set when the peer answered that it does not exchange this scope.
     *
     * Not an error: the peer's switch is off, so there is nothing to exchange and nothing to
     * report as broken. Kept apart from [error] so the summary can say "跳过" instead of
     * "部分同步失败", which is what the user saw when this was an HTTP 404.
     */
    val skippedReason: String? = null,
  ) {
    val success: Boolean get() = error == null

    val skipped: Boolean get() = skippedReason != null
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
      } catch (error: SyncTransportException) {
        if (error.reason == SyncFailureReason.SCOPE_DISABLED) {
          outcomes += skippedPull(scope, peer)
          continue
        }
        outcomes += failed(scope, peer, error.message ?: "拉取失败")
        continue
      } catch (error: Exception) {
        outcomes += failed(scope, peer, error.message ?: "拉取失败")
        continue
      }

      var pulled = 0
      if (remote != null) {
        val applied = engine.applyPayload(remote, peer)
        pulled = applied.applied
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
      var pushSkipped: String? = null
      for (payload in payloads) {
        if (payload.recordCount == 0) {
          // Nothing to send: skip the round trip instead of pushing an empty document.
          continue
        }
        val response = try {
          transport.push(peer, payload)
        } catch (error: SyncTransportException) {
          if (error.reason == SyncFailureReason.SCOPE_DISABLED) {
            // The peer has this scope switched off, so the rest of the parts would be refused
            // too. Stop here: pushing on would only repeat a refusal we already understand.
            pushSkipped = SKIPPED_NOTE
            break
          }
          pushError = error.message ?: "推送失败"
          engine.recordPush(peer, scope, itemCount = pushed, success = false, note = pushError)
          break
        } catch (error: Exception) {
          pushError = error.message ?: "推送失败"
          engine.recordPush(peer, scope, itemCount = pushed, success = false, note = pushError)
          break
        }
        if (response != null) {
          val applied = engine.applyPayload(response, peer)
          pulled += applied.applied
        }
        pushed += payload.recordCount
      }

      if (pushSkipped != null) {
        engine.recordPush(peer, scope, itemCount = 0, success = true, note = pushSkipped)
        outcomes += ScopeOutcome(scope, pulled = pulled, pushed = 0, skippedReason = pushSkipped)
        continue
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
      // The exchange completed, so this peer now holds everything this device holds for the
      // scope. That is what lets old tombstones be dropped without risking a resurrect.
      engine.confirmPeer(peer, scope)
      outcomes += ScopeOutcome(scope, pulled = pulled, pushed = pushed)
    }

    return outcomes
  }

  /** Records and reports an unreachable or rejected peer for one scope. */
  private fun failed(scope: SyncScope, peer: TrustedPeer, reason: String): ScopeOutcome {
    engine.recordPush(peer, scope, itemCount = 0, success = false, note = reason)
    return ScopeOutcome(scope, pulled = 0, pushed = 0, error = reason)
  }

  /** The peer does not exchange this scope: log it as a direction-less zero, not as a failure. */
  private fun skippedPull(scope: SyncScope, peer: TrustedPeer): ScopeOutcome {
    engine.recordPush(
      peer = peer,
      scope = scope,
      itemCount = 0,
      success = true,
      note = SKIPPED_NOTE,
      direction = SyncLogEntry.Direction.PULL,
    )
    return ScopeOutcome(scope, pulled = 0, pushed = 0, skippedReason = SKIPPED_NOTE)
  }

  /** Checks reachability, used to grey out offline peers in the UI. */
  suspend fun ping(peer: TrustedPeer): Boolean = transport.probe(peer)

  companion object {
    /** Wording shared by every place that reports a switched-off scope. */
    const val SKIPPED_NOTE = "对方未开启该同步范围"
  }
}
