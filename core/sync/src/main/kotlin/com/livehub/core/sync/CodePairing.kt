package com.livehub.core.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pairs with whichever device on this network is showing [code].
 *
 * This is the part the old flow got backwards. Pairing used to start by *asking the user
 * for the other device's IP address and port*, then send the code there. WeChat's input
 * method — the thing being copied here — asks for nothing but the six digits: the device
 * showing the code is somewhere on the same network, and finding it is the app's job.
 *
 * Candidates come from discovery plus a sweep of the local subnet. Every one of them is
 * offered the code concurrently, and the first device that accepts wins. A rejection
 * (wrong code) is reported separately from silence (nothing listening), because
 * "配对码不正确" and "没有找到设备" call for completely different things from the user.
 */
class CodePairing(
  private val deviceId: Long,
  private val deviceName: String,
  private val localSecret: String,
  private val localPort: Int = 0,
  /**
   * Concurrent attempts.
   *
   * A /24 is 254 addresses, so this is what keeps a worst-case sweep inside the budget
   * below: 254 / 48 ≈ 6 waves of [attemptTimeoutMillis]. Going wider risks running out of
   * file descriptors on older devices, which is the failure mode that would make pairing
   * fail on exactly the cheap phones most likely to need it.
   */
  private val maxConcurrent: Int = 48,
  /**
   * Per address. A closed port fails in milliseconds, so only filtered addresses — the
   * ones that swallow packets — ever wait this out.
   */
  private val attemptTimeoutMillis: Int = 1_000,
  /** Wall-clock budget for the whole sweep, so the UI never hangs on a filtered host. */
  private val overallBudgetMillis: Long = 10_000,
  private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
  /**
   * Tries [code] against [candidates] and returns the first device that accepts it.
   *
   * Suspending. The attempts run on [dispatcher] and are abandoned as soon as one
   * succeeds: a blocking socket connect cannot be interrupted, so the losers are left to
   * time out on their own rather than joined. That is what keeps a successful pairing
   * instant instead of as slow as the slowest address.
   */
  suspend fun pair(
    code: String,
    candidates: List<DiscoveredPeer>,
    transport: SyncHttpTransport,
  ): CodePairingResult {
    val normalized = code.filter(Char::isDigit)
    if (normalized.length != PairingWindow.CODE_LENGTH) return CodePairingResult.Malformed

    val attempts = candidates.distinctBy { it.host to it.port }
    if (attempts.isEmpty()) return CodePairingResult.NoCandidate

    // Completes with the winner, or with null once every attempt has finished without one.
    // Without the second case a sweep of closed ports — which all fail in milliseconds —
    // still sat out the whole budget before reporting "not found".
    val settled = CompletableDeferred<CodePairingResult.Paired?>()
    val remaining = AtomicInteger(attempts.size)
    val rejections = ConcurrentLinkedQueue<String>()

    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val gate = Semaphore(maxConcurrent)
    attempts.forEach { candidate ->
      scope.launch {
        try {
          // Checked before and after taking a permit: the winner can arrive while this
          // attempt is still queued behind others.
          if (settled.isCompleted) return@launch
          gate.withPermit {
            if (settled.isCompleted) return@withPermit
            when (val outcome = attempt(normalized, candidate, transport)) {
              is CodePairingResult.Paired -> settled.complete(outcome)
              is CodePairingResult.Rejected -> rejections += outcome.reason
              else -> Unit
            }
          }
        } finally {
          if (remaining.decrementAndGet() == 0) settled.complete(null)
        }
      }
    }

    val paired = try {
      withTimeoutOrNull(overallBudgetMillis) { settled.await() }
    } finally {
      // Not joined on purpose: see the method comment. Cancelling stops the queued
      // attempts; the sockets already open close when their own timeout expires.
      scope.cancel()
    }

    if (paired != null) return paired
    // A device that answered "wrong code" is far more informative than addresses that
    // never answered, so it outranks the generic failure when both happened.
    rejections.poll()?.let { return CodePairingResult.Rejected(it) }
    return CodePairingResult.Unreachable(tried = attempts.size)
  }

  /** One handshake round trip against a single candidate address. */
  private suspend fun attempt(
    code: String,
    candidate: DiscoveredPeer,
    transport: SyncHttpTransport,
  ): CodePairingResult {
    val envelope = SyncEnvelope(
      deviceId = deviceId,
      deviceName = deviceName,
      // The secret this device will present from now on; the host stores exactly this.
      secret = localSecret,
      preferredPort = localPort,
      payload = SyncPayload(
        scope = "pair",
        sourceDeviceId = deviceId,
        // Only a de-duplication id on the pair path; the host never applies it.
        sequence = System.currentTimeMillis(),
        createdAt = System.currentTimeMillis(),
        entries = code,
      ),
    )

    val response = try {
      transport.call(
        peer = TrustedPeer(deviceId = 0, deviceName = candidate.deviceName, sharedSecret = "", pairedAt = 0),
        host = candidate.host,
        port = candidate.port,
        path = SyncEnvelope.PATH_PAIR,
        envelope = envelope,
        timeoutMillis = attemptTimeoutMillis,
      )
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: SyncTransportException) {
      // 403 is the peer answering "that code is wrong"; anything else is an address with
      // nothing behind it.
      return if (error.reason == SyncFailureReason.UNAUTHORIZED) {
        CodePairingResult.Rejected(error.message?.takeIf { it.isNotBlank() } ?: "配对码不正确")
      } else {
        CodePairingResult.Unreachable(tried = 0)
      }
    } catch (error: Exception) {
      return CodePairingResult.Unreachable(tried = 0)
    }

    response.error?.let { return CodePairingResult.Rejected(it) }
    if (response.secret.isBlank()) return CodePairingResult.Rejected("对方未返回配对密钥")

    val peerId = if (response.serverDeviceId != 0L) response.serverDeviceId else response.deviceId
    val name = response.serverDeviceName.ifBlank { candidate.deviceName }
    return CodePairingResult.Paired(
      peer = TrustedPeer(
        deviceId = peerId,
        deviceName = name,
        // The secret the host issued for itself; we present it on every later request.
        sharedSecret = response.secret,
        pairedAt = System.currentTimeMillis(),
        lastSeenAt = System.currentTimeMillis(),
      ),
      // Honest address: the port the host says it is listening on, falling back to the one
      // we reached it at. Assuming the default here would break as soon as the preferred
      // port was taken and the server bound an ephemeral one instead.
      address = candidate.copy(
        deviceId = peerId,
        deviceName = name,
        port = response.preferredPort.takeIf { it in 1..65535 } ?: candidate.port,
      ),
    )
  }
}

/** Outcome of a code pairing attempt. */
sealed interface CodePairingResult {
  /** A device accepted the code; [address] is where to reach it from now on. */
  data class Paired(val peer: TrustedPeer, val address: DiscoveredPeer) : CodePairingResult

  /** A device answered but refused the code, or refused for another stated reason. */
  data class Rejected(val reason: String) : CodePairingResult

  /** Nothing answered on any candidate address. */
  data class Unreachable(val tried: Int) : CodePairingResult

  /** No device was known to try, e.g. discovery has not seen anything yet. */
  data object NoCandidate : CodePairingResult

  /** The typed code is not six digits. */
  data object Malformed : CodePairingResult

  /** Message to show the user, or null when [Paired]. */
  val message: String?
    get() = when (this) {
      is Paired -> null
      is Rejected -> reason
      is Unreachable ->
        "没有找到显示该匹配码的设备。请确认两台设备连接同一个 Wi-Fi，且路由器没有开启 AP 隔离。"
      NoCandidate -> "还没发现附近设备。请确认两台设备连接同一个 Wi-Fi。"
      Malformed -> "请输入六位匹配码"
    }
}
