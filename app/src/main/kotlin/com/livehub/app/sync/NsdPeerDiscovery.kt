package com.livehub.app.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.livehub.core.sync.DiscoveredPeer
import com.livehub.core.sync.SyncEnvelope
import com.livehub.core.sync.discovery.PeerDiscovery
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * mDNS/DNS-SD discovery built on Android's [NsdManager].
 *
 * The service is registered as `_livehub._tcp.` and the TXT record carries the device
 * id and name, so a peer can be matched to a [com.livehub.core.sync.TrustedPeer] without
 * an extra round trip.
 *
 * [portProvider] is read when the browse starts, which is *after* `SyncController` has
 * bound its server: the LAN server falls back to an ephemeral port when 47653 is taken,
 * and advertising the constant instead sent every peer to a port nothing was listening
 * on. Peers then reported "对方拒绝连接" on a network where both devices were fine.
 */
class NsdPeerDiscovery(
  private val context: Context,
  private val deviceId: Long,
  private val deviceName: String,
  private val portProvider: () -> Int,
) : PeerDiscovery {

  private val managers = mutableMapOf<Long, DiscoveredPeer>()

  override val isSupported: Boolean = true

  override fun known(): List<DiscoveredPeer> = synchronized(managers) { managers.values.toList() }

  override fun discover(): Flow<DiscoveredPeer> = callbackFlow {
    val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    if (nsd == null) {
      awaitClose { }
      return@callbackFlow
    }

    val registrationListener = object : NsdManager.RegistrationListener {
      override fun onServiceRegistered(info: NsdServiceInfo) {
        Log.d(TAG, "registered ${info.serviceName}")
      }

      override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
        Log.w(TAG, "registration failed: $errorCode")
      }

      override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

      override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
    }

    val serviceInfo = NsdServiceInfo().apply {
      serviceName = "$SERVICE_NAME_PREFIX$deviceId"
      serviceType = SERVICE_TYPE
      port = portProvider()
      setAttribute("deviceId", deviceId.toString())
      setAttribute("deviceName", deviceName)
    }

    runCatching { nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener) }

    val discoveryListener = object : NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(serviceType: String) {
        Log.d(TAG, "discovery started")
      }

      override fun onServiceFound(info: NsdServiceInfo) {
        if (info.serviceType != SERVICE_TYPE) return
        nsd.resolveService(
          info,
          object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
              val peerId = serviceInfo.attributes["deviceId"]
                ?.toString(Charsets.UTF_8)?.toLongOrNull() ?: return
              if (peerId == deviceId) return
              val host = serviceInfo.host?.hostAddress ?: return
              val name = serviceInfo.attributes["deviceName"]
                ?.toString(Charsets.UTF_8)
                ?: serviceInfo.serviceName
              val peer = DiscoveredPeer(
                deviceId = peerId,
                deviceName = name,
                host = host,
                port = serviceInfo.port,
                discovered = true,
              )
              synchronized(managers) { managers[peerId] = peer }
              trySendBlocking(peer)
            }
          },
        )
      }

      override fun onServiceLost(info: NsdServiceInfo) {
        // The instance name is the only identifier that is the same on both sides.
        //
        // This compared `it.value.deviceName` — the human-readable name from the TXT record
        // ("Xiaomi 13") — against `info.serviceName` ("LiveHub-12345"). Those are two different
        // spaces, so the predicate never matched and the table only ever grew: `known()` kept
        // reporting devices that had gone away, one entry per peer id, for the life of the process.
        // It appeared to work only when the TXT attribute was missing, because the resolve path
        // falls back to `serviceName` as the display name in exactly that case.
        val lostId = info.serviceName?.removePrefix(SERVICE_NAME_PREFIX)?.toLongOrNull() ?: return
        synchronized(managers) { managers.remove(lostId) }
      }

      override fun onDiscoveryStopped(serviceType: String) = Unit

      override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

      override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }

    awaitClose {
      runCatching { nsd.unregisterService(registrationListener) }
      runCatching { nsd.stopServiceDiscovery(discoveryListener) }
    }
  }

  companion object {
    const val SERVICE_TYPE = "_livehub._tcp."

    /**
     * Every instance is named `LiveHub-<deviceId>`.
     *
     * `onServiceLost` only receives the instance name (the TXT record is not resolved on that
     * callback), so this prefix is what lets it recover the device id it has to evict.
     */
    private const val SERVICE_NAME_PREFIX = "LiveHub-"

    private const val TAG = "LiveHubDiscovery"
  }
}
