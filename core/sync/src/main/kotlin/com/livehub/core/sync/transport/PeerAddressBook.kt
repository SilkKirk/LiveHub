package com.livehub.core.sync.transport

import com.livehub.core.sync.DiscoveredPeer
import com.livehub.core.sync.SyncEnvelope
import com.livehub.core.sync.TrustedPeer
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Remembers where each paired peer was last seen and keeps manual entries.
 *
 * mDNS discovery only runs while the sync screen is visible, so the engine needs a
 * way to reach a device that is no longer advertising. Addresses are keyed by device id
 * and replaced on every discovery event.
 */
class PeerAddressBook {
  private val addresses = mutableMapOf<Long, DiscoveredPeer>()

  fun remember(peer: DiscoveredPeer) {
    addresses[peer.deviceId] = peer
  }

  fun forget(deviceId: Long) {
    addresses.remove(deviceId)
  }

  fun addressFor(peer: TrustedPeer): DiscoveredPeer? = addresses[peer.deviceId]

  fun lookup(deviceId: Long): DiscoveredPeer? = addresses[deviceId]

  fun snapshot(): List<DiscoveredPeer> = addresses.values.toList()

  fun clear() = addresses.clear()

  /** Adds a manually entered peer (host + port typed by the user). */
  fun addManual(deviceId: Long, name: String, host: String, port: Int) {
    addresses[deviceId] = DiscoveredPeer(
      deviceId = deviceId,
      deviceName = name,
      host = host,
      port = port,
      discovered = false,
    )
  }

  companion object {
    /** Picks the IPv4 address that is most likely reachable from another device. */
    fun preferredLocalAddress(): String? = runCatching {
      val candidates = java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filterNot { it.isLoopbackAddress }
        .mapNotNull { it.hostAddress }

      // Prefer the conventional private ranges, in the order home routers assign them.
      candidates.firstOrNull { it.startsWith("192.168.") }
        ?: candidates.firstOrNull { it.startsWith("10.") }
        ?: candidates.firstOrNull { it.startsWith("172.") }
        ?: candidates.firstOrNull()
    }.getOrNull()
  }
}

/** Parses `host:port` typed by the user, tolerating a missing port. */
fun parseManualAddress(input: String, defaultPort: Int = 47653): Pair<String, Int>? {
  val trimmed = input.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
  if (trimmed.isEmpty()) return null
  val colon = trimmed.lastIndexOf(':')
  if (colon > 0 && trimmed.count { it == ':' } == 1) {
    val host = trimmed.substring(0, colon)
    val port = trimmed.substring(colon + 1).toIntOrNull() ?: return null
    if (host.isBlank() || port !in 1..65535) return null
    return host to port
  }
  return if (InetAddress.getByName(trimmed) != null) trimmed to defaultPort else trimmed to defaultPort
}
