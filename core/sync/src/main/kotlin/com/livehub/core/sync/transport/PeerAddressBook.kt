package com.livehub.core.sync.transport

import com.livehub.core.sync.DiscoveredPeer
import com.livehub.core.sync.TrustedPeer
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers where each paired peer was last seen and keeps manual entries.
 *
 * A [TrustedPeer] carries the pairing secret but **not** an address, so this table is the
 * only thing that knows where the other device is. It therefore has to outlive the process:
 * mDNS is the fast path for re-learning an address, but it is also the first thing a home
 * router breaks, and an app that had forgotten every address could only ever report
 * "不在线" on a network where the peer was sitting right there. [initial] seeds the table
 * from disk and [onChange] writes it back after every change.
 *
 * Reads happen from socket, discovery and UI threads at once, so the table is concurrent.
 */
class PeerAddressBook(
  initial: Collection<DiscoveredPeer> = emptyList(),
  /** Persists the whole table when it changes; called without holding any lock. */
  private val onChange: (List<DiscoveredPeer>) -> Unit = {},
) {
  private val addresses = ConcurrentHashMap<Long, DiscoveredPeer>()

  init {
    initial.forEach { addresses[it.deviceId] = it }
  }

  fun remember(peer: DiscoveredPeer) {
    val previous = addresses.put(peer.deviceId, peer)
    // mDNS re-announces the same device every few seconds; only a real change is written.
    if (previous != peer) onChange(snapshot())
  }

  fun forget(deviceId: Long) {
    if (addresses.remove(deviceId) != null) onChange(snapshot())
  }

  fun addressFor(peer: TrustedPeer): DiscoveredPeer? = addresses[peer.deviceId]

  fun lookup(deviceId: Long): DiscoveredPeer? = addresses[deviceId]

  fun snapshot(): List<DiscoveredPeer> = addresses.values.toList()

  /** Adds a manually entered peer (host + port typed by the user). */
  fun addManual(deviceId: Long, name: String, host: String, port: Int) {
    remember(
      DiscoveredPeer(
        deviceId = deviceId,
        deviceName = name,
        host = host,
        port = port,
        discovered = false,
      ),
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
