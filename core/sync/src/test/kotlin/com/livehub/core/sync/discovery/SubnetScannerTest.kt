package com.livehub.core.sync.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The subnet sweep that makes code pairing work when multicast does not.
 *
 * The bounds are the point: a sweep that reached past RFC 1918 or past a /24 would turn
 * "pair with a phone" into "scan somebody's network", which is both slow and not something
 * an app should do uninvited.
 */
class SubnetScannerTest {

  @Test
  fun `private ranges are recognised`() {
    listOf("10.0.0.1", "192.168.1.5", "192.168.255.254", "172.16.0.1", "172.31.255.254").forEach {
      assertTrue(SubnetScanner.isPrivate(it), "$it should be private")
    }
  }

  @Test
  fun `public and malformed addresses are not swept`() {
    listOf(
      "8.8.8.8",
      // 172.32 is outside 172.16/12. A `startsWith("172.")` check would wrongly admit it.
      "172.32.0.1",
      "172.15.255.255",
      "192.169.1.1",
      "127.0.0.1",
      "not-an-address",
      "10.0.0",
      "",
    ).forEach {
      assertFalse(SubnetScanner.isPrivate(it), "$it must not be swept")
    }
  }

  @Test
  fun `candidates cover a full /24 on the sync port`() {
    val candidates = SubnetScanner.candidates(port = 47653, hostSuffixes = 1..4)

    // The suffix range is injectable precisely so this can be asserted without enumerating
    // 254 addresses per interface of whatever machine runs the test.
    assertTrue(candidates.isNotEmpty() || SubnetScanner.localSubnets().isEmpty())
    candidates.forEach { assertTrue(it.port == 47653) }
    candidates.forEach { assertTrue(it.host.substringAfterLast('.').toInt() in 1..254) }
    // Sweep entries are not discovery results and must not claim to be.
    candidates.forEach { assertFalse(it.discovered) }
  }

  @Test
  fun `the sweep never exceeds 254 hosts per subnet`() {
    assertEquals(254, SubnetScanner.MAX_HOSTS_PER_SUBNET)
    val candidates = SubnetScanner.candidates(port = 1)
    val perPrefix = candidates.groupBy { it.host.substringBeforeLast('.') }
    perPrefix.forEach { (prefix, hosts) ->
      assertTrue(hosts.size <= SubnetScanner.MAX_HOSTS_PER_SUBNET, "$prefix has ${hosts.size} hosts")
    }
  }

  @Test
  fun `candidate hosts are distinct`() {
    val candidates = SubnetScanner.candidates(port = 1)
    assertEquals(candidates.size, candidates.distinctBy { it.host }.size)
  }

  @Test
  fun `every local subnet is a private prefix`() {
    SubnetScanner.localSubnets().forEach { prefix ->
      val probe = "$prefix.1"
      assertTrue(SubnetScanner.isPrivate(probe), "sweeping a non-private prefix: $prefix")
    }
  }
}
