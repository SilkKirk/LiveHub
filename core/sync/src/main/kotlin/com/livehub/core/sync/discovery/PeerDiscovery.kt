package com.livehub.core.sync.discovery

import com.livehub.core.sync.DiscoveredPeer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * LAN peer discovery.
 *
 * The interface exists so the sync screen can be driven by mDNS on a real device and by
 * a scripted fake in tests; the app only ever observes a [Flow] of peers.
 */
interface PeerDiscovery {
  /** Emits every peer seen while the flow is collected; discovery starts on collect. */
  fun discover(): Flow<DiscoveredPeer>

  /** Returns the currently known peers without starting a new scan. */
  fun known(): List<DiscoveredPeer>

  val isSupported: Boolean
}

/** Discovery that never finds anything; used on the JVM and in tests. */
class NoOpPeerDiscovery : PeerDiscovery {
  override fun discover(): Flow<DiscoveredPeer> = callbackFlow {
    awaitClose { }
  }

  override fun known(): List<DiscoveredPeer> = emptyList()

  override val isSupported: Boolean = false
}

/**
 * Fixed-peer discovery: returns the peers registered up front.
 *
 * Used by the emulator tests and by users who type an address because their router
 * blocks multicast.
 */
class StaticPeerDiscovery(initial: List<DiscoveredPeer> = emptyList()) : PeerDiscovery {
  private val peers = initial.toMutableList()

  fun add(peer: DiscoveredPeer) {
    peers.removeAll { it.deviceId == peer.deviceId }
    peers.add(peer)
  }

  override fun discover(): Flow<DiscoveredPeer> = callbackFlow {
    peers.forEach { trySend(it) }
    awaitClose { }
  }

  override fun known(): List<DiscoveredPeer> = peers.toList()

  override val isSupported: Boolean = true
}
