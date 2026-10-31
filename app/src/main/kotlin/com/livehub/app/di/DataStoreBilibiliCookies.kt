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

/** Persists the Bilibili cookie header so a login survives restarts. */
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
    cached = merged
    runBlocking { context.biliDataStore.edit { it[keyCookie] = merged } }
  }

  override fun clear() {
    cached = ""
    runBlocking { context.biliDataStore.edit { it.remove(keyCookie) } }
  }

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
