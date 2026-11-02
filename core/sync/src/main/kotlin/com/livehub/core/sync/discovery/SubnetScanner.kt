package com.livehub.core.sync.discovery

import com.livehub.core.sync.DiscoveredPeer
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Candidate addresses on this device's own subnet.
 *
 * mDNS is the fast path and the one that carries real device names, but it is also the
 * first thing a home router breaks: AP isolation and IGMP snooping silently swallow
 * multicast, and the user is left with "未发现其他设备" on a network that is perfectly
 * fine for TCP. Sweeping the local /24 on the sync port covers that case, and pairing by
 * code — unlike pairing by address — makes a guessed address safe to try, because the
 * code is what authenticates.
 *
 * The sweep is deliberately bounded:
 *
 *  * **One /24 only.** Anything wider turns "pair a device" into a network scan; a /16
 *    would be 65k connect attempts and several minutes.
 *  * **RFC 1918 only.** A routable address here would mean scanning somebody else's
 *    network, which is not something an app should do uninvited.
 */
object SubnetScanner {
  /** Upper bound on addresses returned per interface, matching the /24 rule. */
  const val MAX_HOSTS_PER_SUBNET = 254

  /**
   * Host addresses a peer could plausibly occupy on this device's network.
   *
   * @param port the sync port to probe.
   */
  fun candidates(port: Int, hostSuffixes: IntRange = 1..MAX_HOSTS_PER_SUBNET): List<DiscoveredPeer> =
    localSubnets().flatMap { prefix ->
      hostSuffixes.map { suffix ->
        DiscoveredPeer(
          deviceId = 0,
          deviceName = "",
          host = "$prefix.$suffix",
          port = port,
          discovered = false,
        )
      }
    }.distinctBy { it.host }

  /**
   * Private IPv4 /24 prefixes (`192.168.1`, `10.0.0`, …) this device is attached to.
   *
   * Only addresses the device itself holds are inspected, so a VPN interface pointing at
   * a corporate /16 contributes its own /24 and nothing else.
   */
  fun localSubnets(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
      .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
      .flatMap { it.inetAddresses.toList() }
      .filterIsInstance<Inet4Address>()
      .filterNot { it.isLoopbackAddress }
      .filter { isPrivate(it.hostAddress.orEmpty()) }
      .mapNotNull { address -> address.hostAddress?.substringBeforeLast('.') }
      .distinct()
  }.getOrDefault(emptyList())

  /**
   * True for RFC 1918 space.
   *
   * `172.16/12` is checked by second octet rather than by prefix string, because
   * `startsWith("172.")` would also admit 172.32.x.x — public, and not ours to scan.
   */
  fun isPrivate(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    return when {
      octets[0] == 10 -> true
      octets[0] == 192 && octets[1] == 168 -> true
      octets[0] == 172 && octets[1] in 16..31 -> true
      else -> false
    }
  }
}
