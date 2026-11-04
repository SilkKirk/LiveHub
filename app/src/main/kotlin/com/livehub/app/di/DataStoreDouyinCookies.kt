package com.livehub.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.livehub.platform.douyin.DouyinCookieStore
import com.livehub.platform.douyin.mergeDouyinCookies
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.douyinDataStore: DataStore<Preferences> by preferencesDataStore(name = "livehub_douyin")

/**
 * Persists Douyin's cookies so both the negotiated `ttwid` and a QR login survive restarts.
 *
 * Writes merge (see [mergeDouyinCookies]): negotiation adds `ttwid`, a login adds `sessionid`
 * and friends, and replacing either half would silently undo the other — the visible symptom
 * being "logged in, but search still asks me to log in".
 *
 * Without persistence the `ttwid` would be negotiated again on every cold start. That is
 * cheap (one page load), but the first request after launch would still race the negotiation,
 * and the whole point of storing it is that the *first* room-open already carries a valid
 * cookie — that was a reported bug in an earlier round.
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
    val merged = mergeDouyinCookies(header(), header)
    cached = merged
    runBlocking { context.douyinDataStore.edit { it[keyCookie] = merged } }
  }

  override fun clear() {
    cached = ""
    runBlocking { context.douyinDataStore.edit { it.remove(keyCookie) } }
  }
}
