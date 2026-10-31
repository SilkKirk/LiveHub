package com.livehub.app.ui.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders a QR code to an [ImageBitmap].
 *
 * ZXing is already a dependency for scanning; reusing it for *generation* avoids adding
 * a second QR library just for the login screen.
 */
object QrCodeRenderer {
  /**
   * @param content the URL to encode.
   * @param sizePx width and height of the produced bitmap.
   * @param marginModules quiet zone, in QR modules.
   */
  fun render(
    content: String,
    sizePx: Int = 512,
    marginModules: Int = 1,
    dark: Int = Color.BLACK,
    light: Int = Color.WHITE,
  ): ImageBitmap {
    val hints = mapOf(
      EncodeHintType.CHARACTER_SET to "UTF-8",
      EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
      EncodeHintType.MARGIN to marginModules,
    )

    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)

    for (y in 0 until height) {
      val offset = y * width
      for (x in 0 until width) {
        pixels[offset + x] = if (matrix.get(x, y)) dark else light
      }
    }

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap.asImageBitmap()
  }
}
