package com.livehub.app.sync

/**
 * The local-network permission Android 17 introduced, and the only rule the app needs about it.
 *
 * From API 37 the platform enforces "local network protection" for every app whose `targetSdk` is
 * 37 or higher: traffic to LAN addresses — outgoing TCP, incoming connections, UDP unicast,
 * multicast and broadcast, `.local` resolution, and framework classes such as `NsdManager` — is
 * blocked until the user grants this runtime permission. This app's entire sync feature lives on
 * exactly that surface (a `ServerSocket` bind, raw sockets in the transport, a subnet sweep, mDNS
 * discovery), and without the grant those calls do not fail with a clear error: they time out, so
 * the user would see "对方拒绝连接" or "不在线" on a network that is working perfectly.
 *
 * Kept as a pure rule rather than scattering `SDK_INT` comparisons and permission lookups through
 * the view model, because the interesting half — "may the sync surface run at all?" — has to be
 * unit-testable without an API 37 device or a Robolectric jar for it.
 */
object LocalNetworkAccess {

  /**
   * The permission's manifest name.
   *
   * A literal rather than `Manifest.permission.ACCESS_LOCAL_NETWORK`: the constant only exists in
   * the API 37 SDK stubs, and string literals keep this file compilable and honest about what is
   * actually required on the wire.
   */
  const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

  /** First API level that enforces the permission. Below it the permission does not exist. */
  const val REQUIRED_FROM_SDK = 37

  /**
   * Whether the platform will refuse LAN traffic without the grant.
   *
   * Below [REQUIRED_FROM_SDK] this is always false — not as a lenient default but because the
   * platform does not gate anything there, and asking for a permission that does not exist would
   * only produce a confusing "denied".
   */
  fun enforced(sdk: Int): Boolean = sdk >= REQUIRED_FROM_SDK

  /** Whether LAN work has to be held back: the platform gates it and the user has not granted. */
  fun blocked(sdk: Int, granted: Boolean): Boolean = enforced(sdk) && !granted
}
