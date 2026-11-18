package com.livehub.app.ui

import com.livehub.app.di.AppContainer
import com.livehub.app.sync.LocalNetworkAccess
import com.livehub.app.sync.SyncController
import com.livehub.core.network.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The sync surface of the app: everything around LAN sync except the protocol itself — when the
 * server may listen, the pairing page's code and discovery, pairing, and the exchange.
 *
 * **Why this is not part of the ViewModel any more.** Round 130 split it out: roughly six hundred
 * of the shell's lines were this one screen's lifecycle and lifetime policy, and they change for
 * different reasons than "which tab is open". Nothing in here was wrong; the file had simply
 * become two jobs. The KDoc that argues each decision moved with the code it explains — that
 * documentation is the reason this is a move and not a rewrite.
 *
 * **One screen state, not two.** [state] is [MainViewModel]'s own `_state`, handed in rather than
 * copied, so a write here is a write the sync page draws on the next frame. The guards this class
 * owns (`syncInFlight`, `syncServerJob`) are private to it because their only readers are the
 * methods below.
 *
 * **The threading contract is the one the ViewModel had.** Every entry point is called on the main
 * dispatcher: `UiAction` handlers and `MainActivity`'s `onStart`/`onStop` are main-thread callbacks,
 * and everything below starts in [scope] — the ViewModel's `viewModelScope`, which is
 * `Dispatchers.Main.immediate`. `unpair` is the one body that hops to `Dispatchers.IO`, and it only
 * touches [state] afterwards; a `StateFlow` is safe to publish from any thread, and the screens
 * collect it on the main one.
 */
internal class SyncSurface(
  private val container: AppContainer,
  /**
   * The ViewModel's `viewModelScope`, so everything started here still dies with the screen. Taken
   * as a parameter instead of by being a ViewModel: this class is a slice of one, not another one.
   */
  private val scope: CoroutineScope,
  /**
   * The one screen state, owned by [MainViewModel]; see the class comment for why it is passed in.
   */
  private val state: MutableStateFlow<UiState>,
  /**
   * The platform's API level and whether the local-network permission is granted.
   *
   * Injected rather than read from `Build` inside the class so the interesting half — "what happens
   * on API 37 when the user has not granted it" — is unit-testable. The Robolectric configuration
   * here is API 34, where the permission does not exist at all, so a hard-coded `SDK_INT` check
   * would leave that branch untested and unexercised until it broke a real device.
   */
  private val localNetworkSdk: () -> Int,
  private val localNetworkGranted: () -> Boolean,
) {

  // The policy's inputs. Its third answer — "is anything paired" — is read from [state], because
  // the sync page shows the same list.

  /**
   * Whether the app is in the foreground, as reported by `MainActivity.onStart`/`onStop`.
   *
   * Starts false and is only ever set by the activity: it is one of the three conditions that
   * decide whether the LAN server may listen (see [autoSyncWanted]). A ViewModel that guessed
   * "foreground" from being constructed would open a port from a background start.
   */
  private var appForeground: Boolean = false

  /**
   * When the last automatic sync ran, for [AUTO_SYNC_MIN_INTERVAL_MILLIS].
   *
   * In memory only, on purpose: this is a *throttle*, not a record. Persisting it would mean a
   * disk read on every foreground entry to answer "was that recently?", and a user who force
   * stopped the app is going to get one extra exchange, which is harmless.
   */
  private var lastAutoSyncAtMillis: Long? = null

  // The guards. Both are plain fields because every writer runs on the main dispatcher; see the
  // class comment.

  /**
   * Whether an exchange is running; see [syncNow].
   *
   * A plain field rather than a `Flow`, and safe as one because every writer runs on the main
   * dispatcher: `UiAction.SyncNow` is dispatched from a click handler and `autoSyncIfDue` from a
   * `viewModelScope` coroutine.
   */
  private var syncInFlight: Boolean = false

  /**
   * The start of the LAN server, which is a socket bind and therefore not synchronous any more.
   *
   * Held so a second caller can wait for the same bind instead of starting another one; see
   * [startSyncServer] and [awaitSyncServer].
   */
  private var syncServerJob: Job? = null

  /** Cancelled with the page and with the server; see [startDiscovery] and [stopSyncServer]. */
  private var discoveryJob: Job? = null

  /** The code's remaining life; cancelled wherever the code is revoked. */
  private var pairingCountdownJob: Job? = null

  // ------------------------------------------------------------------ lifecycle

  /**
   * The app came to the foreground, reported by `MainActivity.onStart` through
   * [MainViewModel.onAppForeground].
   *
   * This is what replaced "the sync server lives only while the sync page is open": from here on,
   * a paired and switched-on installation listens (and syncs once) just because the app is on
   * screen. Two things happen, in this order:
   *
   * 1. the server is brought up — or, when the sync page is the one on screen, that page is
   *    re-armed, because [onAppBackground] stopped the server and took the code with it;
   * 2. one silent exchange runs if [shouldAutoSync] allows it.
   */
  fun onAppForeground() {
    appForeground = true
    if (state.value.screen == Screen.Sync) {
      // Not `maybeStartSyncServer`: leaving the app stopped the server *and* revoked the code, so
      // the page on screen has to be re-armed with a new one or it would show nothing usable.
      enterSyncScreen()
    } else {
      maybeStartSyncServer()
    }
    autoSyncIfDue()
  }

  /**
   * The app left the foreground, reported by `MainActivity.onStop` through
   * [MainViewModel.onAppBackground].
   *
   * Everything network-facing goes down, including the pairing code: a socket with nobody looking
   * at it is the battery and attack-surface half of the old contract, and that half was right.
   * Coming back re-arms whatever page is open.
   */
  fun onAppBackground() {
    appForeground = false
    stopSyncServer()
  }

  /**
   * Runs one exchange after a foreground entry, if the throttle and the switch allow it.
   *
   * Silent, which is the point: [syncNow] reports through [UiState.message], and that is a
   * snackbar. The user pressed nothing here — a toast appearing because they unlocked their phone
   * would be noise. The exchange still lands in [UiState.syncLog], which is where someone who
   * wants to know what happened looks.
   *
   * The run starts in a coroutine because it now *waits* for the server first: the bind is off the
   * main thread, and an exchange without a transport reports "同步服务未启动" and returns without
   * exchanging anything — a silent failure nobody would ever see.
   */
  private fun autoSyncIfDue() {
    val now = System.currentTimeMillis()
    if (!shouldAutoSync(
        enabled = state.value.autoSyncEnabled,
        peerCount = state.value.peers.size,
        nowMillis = now,
        lastAutoSyncAtMillis = lastAutoSyncAtMillis,
      )
    ) {
      return
    }
    // Stamped before the run, not after: two foreground entries a second apart (a dialog, a
    // notification) would otherwise both start an exchange, and the second would only be answered
    // by the throttle once the first had finished.
    lastAutoSyncAtMillis = now
    scope.launch {
      if (!awaitSyncServer()) return@launch
      syncNow(silent = true)
    }
  }

  /**
   * Records what the user answered to the permission prompt and, if it was a yes, starts whatever
   * the answer unblocked.
   *
   * Called from the activity rather than from a coroutine here because only an activity can show the
   * dialog. It is also the only place that may clear [UiState.localNetworkBlocked] on a yes: the flag
   * is re-derived from the live grant by [localNetworkBlocked] on every attempt, so clearing it is
   * just "stop reporting a condition that no longer holds". A refusal leaves it set on purpose — the
   * sync page has to keep saying why it cannot work, and not re-asking in this process is the
   * activity's job, not this flag's.
   */
  fun onLocalNetworkPermissionResult(granted: Boolean) {
    if (!granted) {
      // `localNetworkBlocked` is deliberately left set: the sync page keeps saying why it cannot
      // work, and the activity will not put the prompt up again in this process.
      state.update {
        it.copy(
          message = if (LocalNetworkAccess.enforced(localNetworkSdk())) {
            "没有本地网络权限，同步无法使用。可以在系统设置里为 LiveHub 打开「附近的设备」。"
          } else {
            null
          },
        )
      }
      return
    }
    state.update { it.copy(localNetworkBlocked = false) }
    // The user granted it while something was already waiting on it: try again now rather than at
    // the next foreground entry.
    if (appForeground || state.value.screen == Screen.Sync) {
      startSyncServer()
      if (state.value.screen == Screen.Sync) startDiscovery()
    }
  }

  // --------------------------------------------------------------------- server

  /**
   * Brings the LAN server up, off the main thread, and publishes the address once it is listening.
   *
   * One job at a time: the foreground callback, the sync page and the discovery button all ask
   * within the same frame, and a second concurrent bind would race the first for the port.
   *
   * **What can be read, and when.** `SyncController.startAsync()` suspends until the socket has
   * actually bound, so `UiState.localAddress` is the app's only trustworthy answer to "where can
   * peers reach me": it is written here, after the bind, from the address the controller read in
   * that same hop — and it stays empty until then, and after any [stopSyncServer]. Nobody may read
   * `SyncController.serverAddress` directly and treat null/empty as "no LAN" while this is in
   * flight; code that must observe readiness either holds this job ([awaitSyncServer]) or polls
   * `isRunning`.
   */
  private fun startSyncServer() {
    // Nothing may touch the LAN without the permission: on API 37+ the failure is a timeout, not an
    // error, so the socket would appear to bind and then never answer. The flag it sets is what the
    // sync page and the activity act on.
    if (localNetworkBlocked()) return
    if (syncServerJob?.isActive == true) return
    syncServerJob = scope.launch {
      val address = try {
        container.syncController.startAsync()
      } catch (cancelled: CancellationException) {
        // Cancelled by `stopSyncServer` (or by the scope going away): the controller refuses to
        // publish a server whose request predates the stop, and nothing must be written here.
        throw cancelled
      } catch (error: Exception) {
        // A failed bind must not take the ViewModel's scope down with it: the page simply shows no
        // address, and pairing reports "同步服务未启动" the way it always did.
        ""
      }
      // Checked on the line that publishes, with no suspension in between: `stopSyncServer` also
      // runs on this thread, so it cannot slip between the check and the write and leave a dead
      // address in the state.
      val listening = container.syncController.isRunning
      state.update { it.copy(localAddress = if (listening) address else "") }
    }
  }

  /**
   * Ensures the server is listening, waiting for an in-flight bind instead of racing it.
   *
   * The bind moved off the main thread, so an exchange started immediately after asking for the
   * server would find no transport and answer "同步服务未启动" without exchanging anything — which
   * is what the automatic sync must not do, since nobody asked for it and there is no snackbar to
   * explain the outcome.
   */
  private suspend fun awaitSyncServer(): Boolean {
    // Reuses the request that is already in flight: the foreground entry asked for the server a
    // moment before this, and replacing that job would only start the same bind twice.
    if (syncServerJob?.isActive != true) startSyncServer()
    syncServerJob?.join()
    return container.syncController.isRunning
  }

  /**
   * Stops the LAN server and collects everything it owned.
   *
   * The UI mirrors go too: `SyncController.stop()` cancels the pairing window as well, so a code
   * left in the state would be shown on the next visit while nothing accepted it, and
   * [UiState.localAddress] would advertise a port nobody is listening on.
   */
  private fun stopSyncServer() {
    discoveryJob?.cancel()
    discoveryJob = null
    pairingCountdownJob?.cancel()
    pairingCountdownJob = null
    // Dropped rather than awaited: a start that is still binding is refused by the controller's own
    // stop generation, and this handle has to go or the next start would see a job that is "in
    // flight", will never publish, and skip itself.
    syncServerJob?.cancel()
    syncServerJob = null
    container.syncController.stop()
    state.update {
      it.copy(
        pairingCode = "",
        pairingCodeSecondsLeft = 0,
        discoveredPeers = emptyList(),
        discovering = false,
        localAddress = "",
      )
    }
  }

  /**
   * Where peers can reach this device, or `""` while nothing is listening yet.
   *
   * `SyncController.serverAddress` documents that it is only meaningful after a bind has landed,
   * and every caller here runs before that line. Reporting an address anyway means reporting the
   * port field's initial zero (`…:0`), which the sync page draws as if it were a real endpoint.
   * [startSyncServer]'s continuation is the one place that publishes a listening server's address.
   */
  private fun addressIfListening(): String =
    if (container.syncController.isRunning) container.syncController.serverAddress.orEmpty() else ""

  /**
   * Whether the platform is holding LAN traffic back, or `false` when it may proceed.
   *
   * Publishing `localNetworkBlocked` is the *only* useful thing to do here: on API 37+ without the
   * grant the socket calls do not fail with a permission error, they time out, so the app would
   * otherwise report "对方拒绝连接" on a healthy network. The flag is what makes the sync page able
   * to say why, and what makes `MainActivity` ask for the permission.
   */
  private fun localNetworkBlocked(): Boolean {
    val blocked = LocalNetworkAccess.blocked(
      sdk = localNetworkSdk(),
      granted = localNetworkGranted(),
    )
    if (blocked != state.value.localNetworkBlocked) {
      state.update { it.copy(localNetworkBlocked = blocked) }
    }
    return blocked
  }

  // --------------------------------------------------------------------- policy

  /**
   * Whether the LAN server should be up without the sync screen.
   *
   * Three conditions, and all three are necessary. Foreground: a socket in the background is
   * battery and attack surface with nobody watching it. At least one paired device: before pairing
   * there is nobody to talk to, and the pairing flow opens the server explicitly on the sync page
   * — so an unpaired installation exposes no port at all. And the user's switch, which is the
   * whole point of [UiAction.SetAutoSync].
   */
  private fun autoSyncWanted(): Boolean =
    appForeground && state.value.autoSyncEnabled && state.value.peers.isNotEmpty()

  /** Starts the server if the policy wants it. Idempotent; the foreground callback calls it often. */
  private fun maybeStartSyncServer() {
    if (!autoSyncWanted()) return
    startSyncServer()
  }

  /** Applies the policy after anything that could have changed its answer. */
  private fun applyAutoSyncPolicy() {
    if (autoSyncWanted()) {
      maybeStartSyncServer()
    } else if (state.value.screen != Screen.Sync) {
      // The sync page is the one place that needs a server regardless: pairing is what it is for.
      stopSyncServer()
    }
  }

  /**
   * Applies the user's switch: persist it, publish it, then let the policy act on the new answer.
   *
   * The order is load-bearing. [autoSyncWanted] answers from [state], so a policy run before the
   * write would still read the old value — switching off would leave the socket listening until the
   * next restart, which is the bug the switch exists to avoid.
   */
  fun setAutoSync(enabled: Boolean) {
    container.preferences.setAutoSync(enabled)
    state.update { it.copy(autoSyncEnabled = enabled) }
    applyAutoSyncPolicy()
  }

  // ---------------------------------------------------------------- the sync page

  /**
   * Everything the sync screen owns on arrival: a listening server, a fresh code, discovery, the
   * device list, and the countdown that keeps the code's remaining life honest.
   *
   * Shared by navigation and [onAppForeground] on purpose. Returning to the app while this page is
   * open has to re-arm it exactly like arriving does, because [onAppBackground] stops the server,
   * and `SyncController.stop()` cancels the pairing window with it — a page left showing a code
   * nothing accepts is worse than one that re-issues.
   */
  fun enterSyncScreen() {
    // Asks for the server off the main thread; the address arrives later through [startSyncServer]
    // and is published when it does. The code below does not wait for it: a pairing code lives in
    // the window, not in the socket, so it can be issued while the bind is still in flight.
    startSyncServer()
    refreshPeers()
    startDiscovery()
    // Show a fresh code on arrival: the device the user just picked up is the one they
    // want the matching code from, and a stale code from a previous visit may already
    // have expired.
    val code = container.syncController.beginPairing()
    state.update {
      it.copy(
        // Guarded on `isRunning`, not read unconditionally: [SyncController.serverAddress] is only
        // meaningful once the bind has landed (its own KDoc says so), and `startSyncServer` above is
        // asynchronous. Reading it here without the guard produced `"192.168.1.23:0"` — the local
        // address glued to the port field's initial zero — for the frame between this write and the
        // bind's continuation, which is what the sync page draws first.
        localAddress = addressIfListening(),
        discoveredPeers = container.syncController.discoveredPeers(),
        pairingCode = code,
        pairingCodeSecondsLeft = container.syncController.pairingCodeSecondsLeft(),
      )
    }
    startPairingCountdown()
  }

  /**
   * Collects what the sync screen owns when the user leaves it, without deciding the server's fate.
   *
   * The code is a live credential and dies with the page that displayed it whatever happens next;
   * discovery is this page's own battery cost and stops too. Whether the socket survives is the
   * policy's answer ([autoSyncWanted]), which is why an unpaired installation still sees the old
   * "leaving the page shuts it down" behaviour while a paired one keeps syncing in the background
   * of the rest of the app.
   */
  fun leaveSyncScreen() {
    container.syncController.cancelPairing()
    discoveryJob?.cancel()
    discoveryJob = null
    pairingCountdownJob?.cancel()
    pairingCountdownJob = null
    if (autoSyncWanted()) {
      state.update {
        it.copy(
          pairingCode = "",
          pairingCodeSecondsLeft = 0,
          discoveredPeers = emptyList(),
          discovering = false,
        )
      }
    } else {
      stopSyncServer()
    }
  }

  /**
   * Runs mDNS discovery for a bounded window and mirrors every sighting into the UI.
   *
   * Discovery is intentionally time-boxed: keeping an mDNS browse running forever
   * drains the battery and the sync screen is the only place that needs it.
   */
  fun startDiscovery() {
    discoveryJob?.cancel()
    // Polling without the permission would sit through the whole discovery window and then report
    // "没有发现其他设备" — a wrong answer about the network rather than about the permission.
    if (localNetworkBlocked()) return
    // The collector this polls lives inside the running server, so make sure one is coming up.
    // Not awaited: the sightings arrive through the polling loop below either way, and the mDNS
    // browse only starts once the bind has landed.
    startSyncServer()
    discoveryJob = scope.launch {
      state.update { it.copy(discovering = true) }
      val polling = launch {
        repeat(DISCOVERY_POLLS) {
          kotlinx.coroutines.delay(DISCOVERY_POLL_INTERVAL_MILLIS)
          state.update {
            it.copy(discoveredPeers = container.syncController.discoveredPeers())
          }
        }
      }
      polling.join()
      state.update {
        it.copy(
          discovering = false,
          discoveredPeers = container.syncController.discoveredPeers(),
          message = if (it.discoveredPeers.isEmpty()) {
            // Names the one thing the user can actually do. It used to end with "或手动填写对方
            // 地址", pointing at a control that does not exist: the other device's dialog has a
            // six-digit field and nothing else (`MatchingCodeDialog` is deliberately address-free,
            // because reading an IP off another screen is the step this flow exists to remove).
            "没有发现其他设备。请确认两台设备连的是同一个 Wi-Fi，然后在另一台设备上输入这 6 位匹配码。"
          } else {
            null
          },
        )
      }
    }
  }

  fun refreshPeers() {
    state.update {
      it.copy(
        peers = container.preferences.trustedPeers(),
        syncLog = container.syncEngine.logEntries(),
        localAddress = addressIfListening(),
        discoveredPeers = container.syncController.discoveredPeers(),
        pairingCode = container.syncController.pairingCode.orEmpty(),
        pairingCodeSecondsLeft = container.syncController.pairingCodeSecondsLeft(),
      )
    }
  }

  /**
   * Shows a new matching code.
   *
   * Re-issuing also drops the countdown back to a full window, so the UI never shows a
   * code that is already dead.
   */
  fun refreshPairingCode() {
    val code = container.syncController.beginPairing()
    state.update {
      it.copy(
        pairingCode = code,
        pairingCodeSecondsLeft = container.syncController.pairingCodeSecondsLeft(),
      )
    }
    startPairingCountdown()
  }

  /**
   * Keeps [UiState.pairingCodeSecondsLeft] honest while the screen is open.
   *
   * The value is a snapshot, so without this the screen would keep advertising the code's
   * original five minutes for as long as the user stayed on the page — and the other
   * device would be told "配对码已过期" by a screen still claiming 4 分钟.
   */
  private fun startPairingCountdown() {
    pairingCountdownJob?.cancel()
    pairingCountdownJob = scope.launch {
      while (true) {
        val left = container.syncController.pairingCodeSecondsLeft()
        state.update { it.copy(pairingCodeSecondsLeft = left) }
        if (left <= 0) return@launch
        kotlinx.coroutines.delay(1_000)
      }
    }
  }

  // ------------------------------------------------------------ pairing + exchange

  /**
   * Forgets one paired device.
   *
   * Off the main thread, because the three calls below each touch disk: `SyncEngine.unpair` writes
   * the whole peer table, `forgetPeer` writes the address book, and `refreshPeers` re-reads both.
   * That is three blocking round trips from one click. `state` is a `StateFlow`, so publishing the
   * result from a background dispatcher is safe — the screens collect it on the main thread.
   */
  fun unpair(deviceId: Long) {
    scope.launch(Dispatchers.IO) {
      container.syncEngine.unpair(deviceId)
      // Drop the address too: it belongs to a pairing that no longer exists, and leaving it in
      // the persisted table would keep it in play for the next sweep.
      container.syncController.forgetPeer(deviceId)
      refreshPeers()
      state.update { it.copy(message = "已解除配对") }
    }
  }

  /**
   * Pairs with whichever device is showing [code].
   *
   * The whole flow is the code: candidates come from discovery plus a subnet sweep inside
   * [SyncController.pairByCode], so there is no address for the user to read or retype.
   */
  fun pairWithCode(code: String) {
    val digits = code.filter(Char::isDigit)
    if (digits.length != 6) {
      state.update { it.copy(message = "请输入六位匹配码") }
      return
    }
    scope.launch {
      state.update { it.copy(pairing = true) }
      // `finally` rather than a plain sequence: any throw between the handshake and the
      // state reads used to leave the flag stuck and 立即同步 permanently disabled.
      val result = try {
        // `runCatchingCancellable`, not `runCatching`: the sweep is a ten second socket walk, and
        // closing the screen cancels it. A swallowed `CancellationException` would report
        // "配对失败" for a pairing the user called off, and would keep the result of a request
        // nobody is waiting for. `finally` still runs, so the flag is released either way.
        runCatchingCancellable { container.syncController.pairByCode(digits) }
          .getOrElse { Result.failure(it) }
      } finally {
        state.update { it.copy(pairing = false) }
      }
      state.update {
        it.copy(
          peers = container.preferences.trustedPeers(),
          followed = container.followStore.followed(),
          specialFollowKeys = container.followStore.specialKeys(),
          message = result.fold(
            onSuccess = { name -> "已与 $name 关联" },
            onFailure = { error -> error.message ?: "配对失败" },
          ),
        )
      }
    }
  }

  /**
   * Runs one exchange and reports the outcome.
   *
   * [silent] is what separates the two callers: the 立即同步 button wants the summary as a
   * snackbar, while the automatic run on foreground entry must not announce itself. Everything
   * else — the `syncing` flag, the refreshed device list, the exchange log — is the same, because
   * it is the same exchange.
   */
  fun syncNow(silent: Boolean = false) {
    // One exchange at a time.
    //
    // `syncing` is the UI's truth and it is set *inside* the coroutine, so it cannot guard the entry
    // point: the button and the automatic run on a foreground entry are two callers, and the second
    // one passes the check before the first has had a chance to set anything. Two concurrent
    // exchanges then share one `SyncEngine` and one single-connection HTTP server, so they queue
    // behind each other until the socket timeout, and whichever finishes first clears `syncing`
    // while the other is still running — leaving the button live over an exchange in progress.
    // This flag is set synchronously on the caller's thread (always the main one), which closes the
    // window; `syncing` stays for rendering.
    if (syncInFlight) return
    syncInFlight = true
    // Published on the caller's frame as well, so the button is disabled immediately rather than
    // one dispatch later — the flag above is what makes the *entry* safe, this is what makes the
    // screen agree with it.
    state.update { it.copy(syncing = true) }
    scope.launch {
      try {
        // Cancellation is not a sync failure. The exchange opens sockets over several peers, so
        // leaving the app cancels it; `runCatching` turned that into "同步失败" written into a
        // state the screen no longer shows. `finally` below still releases the flags.
        val summary = runCatchingCancellable { container.syncController.syncNow() }
          .getOrElse { error ->
            SyncController.SyncSummary(0, 0, error.message ?: "同步失败")
          }
        state.update {
          it.copy(
            peers = container.preferences.trustedPeers(),
            followed = container.followStore.followed(),
            specialFollowKeys = container.followStore.specialKeys(),
            // Labels arrive inside the records, so a merge can add or remove them.
            followTags = container.followStore.tagsByKey(),
            syncLog = container.syncEngine.logEntries(),
            // A silent run leaves whatever message was already on screen alone — it has no news
            // the user did not ask for, and clearing an unrelated one would be worse than saying
            // nothing.
            message = if (silent) it.message else summary.message,
          )
        }
      } finally {
        // Order matters: the guard is released first, so "the screen says the exchange finished"
        // implies "the next one may start". The other order leaves a window where `syncing` is
        // already false and `syncNow` still refuses.
        syncInFlight = false
        // Also covers a cancelled exchange: the screen is gone by then, but leaving the flag set
        // would be a trap for whoever reads this next.
        state.update { it.copy(syncing = false) }
      }
    }
  }

  /**
   * Tears down what must not outlive the ViewModel: the page's own jobs and the LAN socket.
   *
   * Called from [MainViewModel.onCleared], which is itself only reached through
   * `ViewModelStore.clear()`. The danmaku and player jobs belong to the shell and are cancelled
   * there; these two are this surface's.
   */
  fun shutdown() {
    pairingCountdownJob?.cancel()
    discoveryJob?.cancel()
    container.syncController.stop()
  }

  private companion object {
    const val DISCOVERY_POLLS = 15
    const val DISCOVERY_POLL_INTERVAL_MILLIS = 2_000L
  }
}

/**
 * How long an automatic sync trusts the previous one.
 *
 * Five minutes, the same order as the pairing code's own window: long enough that flicking between
 * apps does not start an exchange every time, short enough that "I added a room on the tablet, let
 * me look at the phone" works without thinking about it. Both devices run this rule independently,
 * so the pair converges within one interval either way.
 */
private const val AUTO_SYNC_MIN_INTERVAL_MILLIS = 5 * 60 * 1000L

/**
 * Whether an arrival in the foreground should run a sync.
 *
 * A pure function taking the clock as an argument rather than reading it, so the whole table —
 * "switched off", "nothing paired", "just synced", "never synced", "it has been a while" — is
 * checkable without waiting five minutes or sleeping a test.
 *
 * The three "no" answers are deliberately in order of how cheap they are to check; the last one
 * treats a clock that moved *backwards* as "recently synced", which fails safe: a missed automatic
 * sync costs one manual tap, while trusting a bogus timestamp could start a sync loop.
 */
internal fun shouldAutoSync(
  enabled: Boolean,
  peerCount: Int,
  nowMillis: Long,
  lastAutoSyncAtMillis: Long?,
): Boolean {
  if (!enabled) return false
  if (peerCount <= 0) return false
  // Never synced in this process: the app was just opened, which is exactly when to look.
  val last = lastAutoSyncAtMillis ?: return true
  return nowMillis - last >= AUTO_SYNC_MIN_INTERVAL_MILLIS
}
