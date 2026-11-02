package com.livehub.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.livehub.platform.douyin.DouyinCookieStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.douyinDataStore: DataStore<Preferences> by preferencesDataStore(name = "livehub_douyin")

/**
 * Persists Douyin's negotiated `ttwid` so it survives restarts.
 *
 * Without this the value would be negotiated again on every cold start. It is cheap (one
 * page load), but the first request after launch would still race the negotiation, and the
 * whole point of storing it is that the *first* room-open already carries a valid cookie —
 * that was the reported bug.
 */
class DataStoreDouyinCookies(private val context: Context) : DouyinCookieStore {
  private val keyCookie = stringPreferencesKey("ttwid_header")

  @Volatile private var cached: String? = null

  override fun header(): String {
    cached?.let { return it }
    val value = runBlocking {
      context.douyinDataStore.data.first()[keyCookie].orEmpty()
    }
    cached = value
    return value
  }

  override fun save(header: String) {
    if (header.isBlank()) return
    val trimmed = header.trim()
    cached = trimmed
    runBlocking { context.douyinDataStore.edit { it[keyCookie] = trimmed } }
  }

  override fun clear() {
    cached = ""
    runBlocking { context.douyinDataStore.edit { it.remove(keyCookie) } }
  }
}
