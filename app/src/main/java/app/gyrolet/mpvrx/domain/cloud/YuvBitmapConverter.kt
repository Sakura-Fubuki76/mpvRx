package app.gyrolet.mpvrx.domain.cloud

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Pure-Kotlin replacement for yume's JNI `YuvToBitmapBridge`.
 *
 * yume ships a native bridge (`yume_yuv` + libyuv) that converts `MediaCodec` output to a `Bitmap`.
 * mpvRx has no such library, so the ported MP4/MKV extractors call this object instead. The
 * JNI version exists for throughput; here correctness and zero native dependencies win, and
 * thumbnails decode a single frame at a time so the extra cost is irrelevant.
 *
 * Handles the `Image.Plane` layout exactly as Android describes it — per-plane `rowStride` /
 * `pixelStride` — which covers both planar (I420, pixelStride 1) and semi-planar (NV12/NV21,
 * pixelStride 2) outputs without needing to special-case the vendor quirks that `forceNV21`
 * existed for.
 */
internal object YuvBitmapConverter {
  /** BT.601 and BT.709 YUV -> RGB coefficients, limited (studio) and full range variants. */
  private class Coefficients(
    val yScale: Float,
    val yOffset: Float,
    val rCr: Float,
    val gCb: Float,
    val gCr: Float,
    val bCb: Float,
  )

  private val BT601_LIMITED =
    Coefficients(
      yScale = 1.16438f,
      yOffset = 16f,
      rCr = 1.59603f,
      gCb = -0.39176f,
      gCr = -0.81297f,
      bCb = 2.01723f,
    )

  private val BT601_FULL =
    Coefficients(
      yScale = 1f,
      yOffset = 0f,
      rCr = 1.40200f,
      gCb = -0.34414f,
      gCr = -0.71414f,
      bCb = 1.77200f,
    )

  private val BT709_LIMITED =
    Coefficients(
      yScale = 1.16438f,
      yOffset = 16f,
      rCr = 1.79274f,
      gCb = -0.21325f,
      gCr = -0.53291f,
      bCb = 2.11240f,
    )

  private val BT709_FULL =
    Coefficients(
      yScale = 1f,
      yOffset = 0f,
      rCr = 1.57480f,
      gCb = -0.18732f,
      gCr = -0.46812f,
      bCb = 1.85560f,
    )

  // MediaFormat.KEY_COLOR_STANDARD values.
  private const val COLOR_STANDARD_BT709 = 1
  private const val COLOR_STANDARD_BT601_PAL = 2
  private const val COLOR_STANDARD_BT601_NTSC = 3
  private const val COLOR_STANDARD_BT2020 = 4

  // MediaFormat.KEY_COLOR_RANGE values.
  private const val COLOR_RANGE_FULL = 1

  private fun coefficientsFor(
    colorStandard: Int,
    colorRange: Int,
  ): Coefficients {
    val full = colorRange == COLOR_RANGE_FULL
    return when (colorStandard) {
      COLOR_STANDARD_BT601_PAL, COLOR_STANDARD_BT601_NTSC -> if (full) BT601_FULL else BT601_LIMITED
      // BT.2020 is approximated with BT.709 coefficients: thumbnails do not benefit from the
      // extra precision, and a wrong-but-close matrix beats refusing to render a frame.
      COLOR_STANDARD_BT709, COLOR_STANDARD_BT2020 -> if (full) BT709_FULL else BT709_LIMITED
      else -> if (full) BT601_FULL else BT601_LIMITED
    }
  }

  private inline fun clamp8(value: Float): Int =
    when {
      value <= 0f -> 0
      value >= 255f -> 255
      else -> value.roundToInt()
    }

  /**
   * Converts a `MediaCodec` YUV `Image` into an ARGB_8888 bitmap cropped to
   * `cropLeft/cropTop/cropWidth/cropHeight`.
   *
   * Buffer positions are respected (`get(position + index)`), so planes that alias one shared
   * buffer — which is how several vendors expose semi-planar output — read correctly.
   */
  fun imageToBitmap(
    yBuf: ByteBuffer,
    yRowStride: Int,
    yPixelStride: Int,
    uBuf: ByteBuffer,
    uRowStride: Int,
    uPixelStride: Int,
    vBuf: ByteBuffer,
    vRowStride: Int,
    vPixelStride: Int,
    cropLeft: Int,
    cropTop: Int,
    cropWidth: Int,
    cropHeight: Int,
    colorStandard: Int,
    colorRange: Int,
    @Suppress("UNUSED_PARAMETER") forceNV21: Boolean,
  ): Bitmap? {
    if (cropWidth <= 0 || cropHeight <= 0) return null

    val yBase = yBuf.position()
    val uBase = uBuf.position()
    val vBase = vBuf.position()
    val yLimit = yBuf.limit()
    val uLimit = uBuf.limit()
    val vLimit = vBuf.limit()

    val chromaRowStride = if (uRowStride > 0) uRowStride else yRowStride / 2
    val chromaPixelStride = if (uPixelStride > 0) uPixelStride else 1
    val coeff = coefficientsFor(colorStandard, colorRange)

    val pixels = IntArray(cropWidth * cropHeight)
    var out = 0
    for (row in 0 until cropHeight) {
      val sourceRow = cropTop + row
      val yRowOffset = yBase + sourceRow * yRowStride
      val chromaRowOffset = (sourceRow shr 1) * chromaRowStride
      for (col in 0 until cropWidth) {
        val sourceCol = cropLeft + col

        val yIndex = yRowOffset + sourceCol * yPixelStride
        val y = if (yIndex < yLimit) yBuf.get(yIndex).toInt() and 0xFF else 0

        val chromaCol = sourceCol shr 1
        val uIndex = uBase + chromaRowOffset + chromaCol * chromaPixelStride
        val vIndex = vBase + (sourceRow shr 1) * vRowStride + chromaCol * vPixelStride
        val u = if (uIndex < uLimit) uBuf.get(uIndex).toInt() and 0xFF else 128
        val v = if (vIndex < vLimit) vBuf.get(vIndex).toInt() and 0xFF else 128

        pixels[out++] = yuvToArgb(y, u, v, coeff)
      }
    }
    return Bitmap.createBitmap(pixels, cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
  }

  /**
   * Converts a raw `MediaCodec` byte buffer when the codec could not hand back an `Image`.
   *
   * `colorFormat` follows `MediaCodecInfo.CodecCapabilities.COLOR_Format*`. Planar (I420) and
   * semi-planar (NV12/NV21) are supported; anything else falls back to the semi-planar layout,
   * which is what essentially every hardware decoder emits.
   */
  fun bufferToBitmap(
    yuvBuffer: ByteBuffer,
    offset: Int,
    colorFormat: Int,
    stride: Int,
    sliceHeight: Int,
    cropLeft: Int,
    cropTop: Int,
    cropWidth: Int,
    cropHeight: Int,
    colorStandard: Int,
    colorRange: Int,
    forceNV21: Boolean,
  ): Bitmap? {
    if (cropWidth <= 0 || cropHeight <= 0 || stride <= 0 || sliceHeight <= 0) return null
    if (colorFormat !in setOf(19, 21, 39, 0x7FA30C00)) return null

    val limit = yuvBuffer.limit()
    val coeff = coefficientsFor(colorStandard, colorRange)
    val pixels = IntArray(cropWidth * cropHeight)

    val isPlanar = colorFormat == COLOR_FORMAT_YUV420_PLANAR
    val chromaStride = if (isPlanar) stride / 2 else stride
    val chromaHeight = sliceHeight / 2
    val yPlaneOffset = offset
    val uPlaneOffset = offset + stride * sliceHeight
    val vPlaneOffset = if (isPlanar) uPlaneOffset + chromaStride * chromaHeight else uPlaneOffset

    var out = 0
    for (row in 0 until cropHeight) {
      val sourceRow = cropTop + row
      val yRowOffset = yPlaneOffset + sourceRow * stride
      val chromaRow = sourceRow shr 1
      for (col in 0 until cropWidth) {
        val sourceCol = cropLeft + col

        val yIndex = yRowOffset + sourceCol
        val y = if (yIndex < limit) yuvBuffer.get(yIndex).toInt() and 0xFF else 0

        val chromaCol = sourceCol shr 1
        val uIndex: Int
        val vIndex: Int
        if (isPlanar) {
          uIndex = uPlaneOffset + chromaRow * chromaStride + chromaCol
          vIndex = vPlaneOffset + chromaRow * chromaStride + chromaCol
        } else {
          // Semi-planar: U and V share interleaved pairs. NV21 is V-first.
          val pairIndex = uPlaneOffset + chromaRow * chromaStride + chromaCol * 2
          if (forceNV21) {
            vIndex = pairIndex
            uIndex = pairIndex + 1
          } else {
            uIndex = pairIndex
            vIndex = pairIndex + 1
          }
        }
        val u = if (uIndex < limit) yuvBuffer.get(uIndex).toInt() and 0xFF else 128
        val v = if (vIndex < limit) yuvBuffer.get(vIndex).toInt() and 0xFF else 128

        pixels[out++] = yuvToArgb(y, u, v, coeff)
      }
    }
    return Bitmap.createBitmap(pixels, cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
  }

  private fun yuvToArgb(
    y: Int,
    u: Int,
    v: Int,
    coeff: Coefficients,
  ): Int {
    val yy = (y - coeff.yOffset) * coeff.yScale
    val cb = u - 128f
    val cr = v - 128f

    val r = yy + coeff.rCr * cr
    val g = yy + coeff.gCb * cb + coeff.gCr * cr
    val b = yy + coeff.bCb * cb

    return (0xFF shl 24) or (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)
  }

  // MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar. Declared locally to keep this file
  // free of a hard dependency on the codec capabilities class.
  private const val COLOR_FORMAT_YUV420_PLANAR = 19

  /** Convenience overload for callers that already hold an [Image]. */
  fun imageToBitmap(
    image: Image,
    colorStandard: Int,
    colorRange: Int,
  ): Bitmap? {
    val planes = image.planes
    if (planes.size < 3) return null
    val crop = image.cropRect
    return imageToBitmap(
      yBuf = planes[0].buffer,
      yRowStride = planes[0].rowStride,
      yPixelStride = planes[0].pixelStride,
      uBuf = planes[1].buffer,
      uRowStride = planes[1].rowStride,
      uPixelStride = planes[1].pixelStride,
      vBuf = planes[2].buffer,
      vRowStride = planes[2].rowStride,
      vPixelStride = planes[2].pixelStride,
      cropLeft = crop.left,
      cropTop = crop.top,
      cropWidth = crop.width(),
      cropHeight = crop.height(),
      colorStandard = colorStandard,
      colorRange = colorRange,
      forceNV21 = false,
    )
  }
}
