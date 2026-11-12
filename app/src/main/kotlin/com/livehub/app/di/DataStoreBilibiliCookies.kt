package com.livehub.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.livehub.platform.bilibili.BilibiliCookieStore
import kotlinx.coroutines.runBlocking

private val Context.biliDataStore: DataStore<Preferences> by preferencesDataStore(name = "livehub_bilibili")

/** One writer for the file, shared by every instance; see [SerialWriteQueue]. */
private val biliWrites = SerialWriteQueue("livehub-bilibili-write")

/**
 * Persists the Bilibili cookie header so a login survives restarts.
 *
 * Reads stay synchronous — adapters ask for the header inside a request — but they are served from
 * the in-memory cache after the first touch, and callers that reach the first read are on IO
 * (`MainViewModel` resolves the login flags on `Dispatchers.IO` now). Writes are handed to
 * [SerialWriteQueue] instead of blocking the caller, so a 退出登录 tap no longer writes a file on the
 * main thread.
 */
class DataStoreBilibiliCookies(private val context: Context) : BilibiliCookieStore {
  private val keyCookie = stringPreferencesKey("cookie_header")

  @Volatile private var cached: String? = null

  override fun header(): String {
    cached?.let { return it }
    val value = runBlocking {
      context.biliDataStore.data.first()[keyCookie].orEmpty()
    }
    cached = value
    return value
  }

  override fun save(rawHeader: String) {
    if (rawHeader.isBlank()) return
    val merged = merge(header(), rawHeader)
    // Published before the write is handed off, so a request issued in the same frame already
    // carries the new cookie — this is what makes "logged in" work without waiting for the disk.
    cached = merged
    biliWrites.submit { context.biliDataStore.edit { it[keyCookie] = merged } }
  }

  override fun clear() {
    cached = ""
    biliWrites.submit { context.biliDataStore.edit { it.remove(keyCookie) } }
  }

  /** Blocks until every write issued by this process has reached disk. Tests only. */
  fun awaitWrites() = biliWrites.awaitIdle()

  private fun merge(existing: String, incoming: String): String {
    val map = LinkedHashMap<String, String>()
    parse(existing).forEach { (k, v) -> map[k] = v }
    parse(incoming).forEach { (k, v) -> map[k] = v }
    return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
  }

  private fun parse(raw: String): List<Pair<String, String>> = raw
    .split(';')
    .mapNotNull { part ->
      val trimmed = part.trim()
      val index = trimmed.indexOf('=')
      if (trimmed.isEmpty() || index <= 0) null else trimmed.substring(0, index) to trimmed.substring(index + 1)
    }
}
