package app.gyrolet.mpvrx.domain.cloud

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer

/** Tiny debug-only checks run on the device when the JNI bridge is first used. */
internal object YuvNativeDiagnostics {
  fun verify() {
    fun bytes(size: Int, value: Int) = ByteBuffer.allocateDirect(size).apply { repeat(size) { put(value.toByte()) }; flip() }
    // BT.601 limited-range red: verify RGBA channel order, padding stride, and buffer positions.
    val y = bytes(32 * 18 + 4, 81).apply { position(4) }
    val u = bytes(16 * 9 + 2, 90).apply { position(2) }
    val v = bytes(16 * 9 + 2, 240).apply { position(2) }
    val red = checkNotNull(YuvToBitmapBridge.imageToBitmap(y, 32, 1, u, 16, 1, v, 16, 1,
      0, 0, 20, 16, 2, 2, false))
    val sheet = Bitmap.createBitmap(40, 32, Bitmap.Config.ARGB_8888)
    var resized: Bitmap? = null
    try {
      val pixel = red.getPixel(10, 8)
      check(Color.red(pixel) > 230 && Color.green(pixel) < 30 && Color.blue(pixel) < 30)
      check(red.width == 20 && red.height == 16)
      check(YuvToBitmapBridge.argbIsMostlySolidColor(red, 0.7f, 30))
      check(YuvToBitmapBridge.compositeToSheet(red, sheet, 1, 1, 20, 16, 2))
      check(sheet.getPixel(30, 24) == pixel)
      check(!YuvToBitmapBridge.compositeToSheet(red, sheet, 2, 1, 20, 16, 2))
      resized = checkNotNull(YuvToBitmapBridge.argbScale(red, 10, 8, FilterMode.BOX))
      check(resized.width == 10 && resized.height == 8)
      check(YuvToBitmapBridge.imageToBitmap(bytes(1, 0), 32, 1, u, 16, 1, v, 16, 1,
        0, 0, 20, 16, 2, 2, false) == null)
      val dy = bytes(10 * 8, 0); val du = bytes(5 * 4, 0); val dv = bytes(5 * 4, 0)
      check(YuvToBitmapBridge.android420ScaleToI420(y, 32, 1, u, 16, 1, v, 16, 1,
        0, 0, 20, 16, dy, du, dv, 10, 8, FilterMode.BOX))
      check((dy.get(0).toInt() and 255) in 79..83)
    } finally { resized?.recycle(); sheet.recycle(); red.recycle() }
  }
}
