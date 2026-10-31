package com.livehub.platform.bilibili

import com.livehub.core.model.Category
import com.livehub.core.model.LiveStatus
import com.livehub.core.model.Platform
import com.livehub.core.model.PlaybackSource
import com.livehub.core.model.RoomPage
import com.livehub.core.model.Streamer
import com.livehub.platform.api.DanmakuEvent
import com.livehub.platform.api.LivePlatformAdapter
import com.livehub.platform.api.PlatformProtocolException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Placeholder adapter; the real implementation lands in the next commit. */
internal class BilibiliAdapter : LivePlatformAdapter {
  override val platform: Platform = Platform.
  override suspend fun categories(): List<Category> = unsupported()
  override suspend fun rooms(categoryId: String, cursor: String?): RoomPage = unsupported()
  override suspend fun search(keyword: String, cursor: String?): RoomPage = unsupported()
  override suspend fun roomDetail(roomId: String): Streamer? = unsupported()
  override suspend fun liveStatus(roomId: String): LiveStatus? = unsupported()
  override suspend fun playback(roomId: String, preferredQuality: Int?): PlaybackSource = unsupported()
  override fun danmaku(roomId: String): Flow<DanmakuEvent> = emptyFlow()
  private fun unsupported(): Nothing =
    throw PlatformProtocolException("$(System.Collections.Hashtable.c) 适配器尚未实现", Platform.)
}
