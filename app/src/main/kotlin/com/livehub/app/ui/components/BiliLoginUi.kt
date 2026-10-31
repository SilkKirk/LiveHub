package com.livehub.app.ui.components

import androidx.compose.ui.graphics.ImageBitmap
/** UI state of the Bilibili login card. */
data class BiliLoginUi(
  val loggedIn: Boolean = false,
  val loading: Boolean = false,
  val qr: ImageBitmap? = null,
  val status: String = "",
)

