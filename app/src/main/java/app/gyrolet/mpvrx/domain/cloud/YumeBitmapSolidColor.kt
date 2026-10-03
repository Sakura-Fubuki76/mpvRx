package app.gyrolet.mpvrx.domain.cloud

import android.graphics.Bitmap
import androidx.core.graphics.get
import app.gyrolet.mpvrx.domain.cloud.SpriteMigrationLog as Logger
import kotlin.math.abs

private var nativeBitmapSolidColorCheckAvailable = true

fun Bitmap.isMostlySolidColor(threshold: Float = 0.7f): Boolean {
    isMostlySolidColorNative(threshold)?.let { return it }
    return isMostlySolidColorKotlin(threshold)
}

private fun Bitmap.isMostlySolidColorNative(threshold: Float): Boolean? {
    if (!nativeBitmapSolidColorCheckAvailable) return null
    if (config != Bitmap.Config.ARGB_8888) return null
    return try {
        YuvToBitmapBridge.argbIsMostlySolidColor(
            bitmap = this,
            threshold = threshold,
            tolerance = 30,
        )
    } catch (_: UnsatisfiedLinkError) {
        nativeBitmapSolidColorCheckAvailable = false
        Logger.w("SolidColorCheck", "Bitmap native solid check unavailable, using Kotlin fallback")
        null
    }
}

private fun Bitmap.isMostlySolidColorKotlin(threshold: Float): Boolean {
    val width = this.width
    val height = this.height

    val marginX = width / 10
    val marginY = height / 10
    val sampleAreaRight = width - marginX
    val sampleAreaBottom = height - marginY

    val gridSize = 10
    val stepX = (sampleAreaRight - marginX) / gridSize
    val stepY = (sampleAreaBottom - marginY) / gridSize

    if (stepX <= 0 || stepY <= 0) return false

    val sampledColors = mutableListOf<Int>()

    for (x in 0 until gridSize) {
        for (y in 0 until gridSize) {
            val pixelX = marginX + x * stepX
            val pixelY = marginY + y * stepY
            if (pixelX < width && pixelY < height) {
                sampledColors.add(this[pixelX, pixelY])
            }
        }
    }

    if (sampledColors.isEmpty()) return false

    val referenceColor = sampledColors[0]
    val referenceR = (referenceColor shr 16) and 0xFF
    val referenceG = (referenceColor shr 8) and 0xFF
    val referenceB = referenceColor and 0xFF

    val tolerance = 30
    val similarCount = sampledColors.count { color ->
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        abs(r - referenceR) <= tolerance &&
            abs(g - referenceG) <= tolerance &&
            abs(b - referenceB) <= tolerance
    }

    return similarCount.toFloat() / sampledColors.size >= threshold
}
