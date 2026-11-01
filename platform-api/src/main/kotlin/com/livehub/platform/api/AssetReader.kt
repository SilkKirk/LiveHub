package com.livehub.platform.api

/**
 * Reads a bundled text resource by name.
 *
 * Three platforms publish their full category tree as a static document rather than a
 * live endpoint, so the app ships the `categories` JSON files and hands the platform
 * adapters a reader. Keeping it a plain function keeps the platform modules free of any
 * Android dependency, which is what lets their tests run on the JVM.
 */
fun interface AssetReader {
  /** Returns the file's text, or null when it is absent. */
  fun read(name: String): String?

  companion object {
    /** Used by tests and by any caller that has no assets. */
    val Empty: AssetReader = AssetReader { null }
  }
}
