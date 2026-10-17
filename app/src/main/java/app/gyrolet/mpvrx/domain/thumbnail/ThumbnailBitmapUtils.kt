/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.thumbnail

import android.graphics.Bitmap
import kotlin.math.abs

internal const val MAX_THUMBNAIL_SIZE = 512
internal const val THUMBNAIL_JPEG_QUALITY = 90

internal fun calculateThumbnailSampleSize(
  width: Int,
  height: Int,
  maxSize: Int = MAX_THUMBNAIL_SIZE,
): Int {
  if (width <= maxSize && height <= maxSize) return 1
  var inSampleSize = 1
  val maxDimension = maxOf(width, height)
  while (maxDimension / (inSampleSize * 2) >= maxSize) {
    inSampleSize *= 2
  }
  return inSampleSize
}

internal fun Bitmap.scaleToThumbnailMax(maxSize: Int = MAX_THUMBNAIL_SIZE): Bitmap {
  if (width <= maxSize && height <= maxSize) return this
  val scale = maxSize.toFloat() / maxOf(width, height)
  val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
  val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
  val scaled = if (config == Bitmap.Config.ARGB_8888 && app.gyrolet.mpvrx.domain.cloud.YuvToBitmapBridge.available)
    app.gyrolet.mpvrx.domain.cloud.YuvToBitmapBridge.argbScale(this, scaledWidth, scaledHeight, 3)
      ?: Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
  else Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
  if (scaled !== this && !isRecycled) recycle()
  return scaled
}

/** Width the frame is shrunk to before the blank-frame analysis; structure survives this. */
private const val SOLID_SAMPLE_WIDTH = 40

/** Per-channel distance from the frame's average colour still counted as "the same colour". */
private const val SOLID_COLOR_TOLERANCE = 10

/** Mean per-channel step between neighbours below which the frame carries no visible edges. */
private const val SOLID_MAX_EDGE = 1.5f

/** Share of samples that must sit on the average colour before the frame reads as one flat field. */
private const val SOLID_SIMILAR_FRACTION = 0.995f

/** How flat a frame is: pixels on the average colour, and how much edge energy it carries. */
internal data class FrameFlatness(
  val similarFraction: Float,
  val meanEdge: Float,
) {
  val isBlank: Boolean
    get() = similarFraction >= SOLID_SIMILAR_FRACTION && meanEdge <= SOLID_MAX_EDGE
}

/**
 * Measures how blank [pixels] look.
 *
 * A black lead-in, a fade or a static colour card is both nearly one colour and almost edge-free.
 * A dark scene still carries shading, noise and edges, so measuring the mean step between
 * neighbours keeps dim footage out of the blank verdict — which a plain colour-distance count
 * could not do. Kept free of Android types so the heuristic is unit-testable.
 */
internal fun measureFrameFlatness(
  pixels: IntArray,
  width: Int,
  height: Int,
): FrameFlatness {
  val size = width * height
  if (width < 2 || height < 2 || pixels.size < size) return FrameFlatness(0f, Float.MAX_VALUE)

  var red = 0L
  var green = 0L
  var blue = 0L
  var opaque = 0
  for (index in 0 until size) {
    val colour = pixels[index]
    if ((colour ushr 24) and 0xFF < 8) continue
    red += (colour shr 16) and 0xFF
    green += (colour shr 8) and 0xFF
    blue += colour and 0xFF
    opaque++
  }
  // Nothing opaque means nothing to show, which is as blank as a frame gets.
  if (opaque == 0) return FrameFlatness(1f, 0f)

  val averageRed = (red / opaque).toInt()
  val averageGreen = (green / opaque).toInt()
  val averageBlue = (blue / opaque).toInt()

  var similar = 0
  var edgeSum = 0L
  var edgeCount = 0
  for (y in 0 until height) {
    for (x in 0 until width) {
      val index = y * width + x
      val colour = pixels[index]
      if ((colour ushr 24) and 0xFF < 8) {
        // Transparent padding on server-side previews: it carries no colour, so treat the sample
        // as matching whatever the average is and let the opaque samples decide.
        similar++
        continue
      }
      val r = (colour shr 16) and 0xFF
      val g = (colour shr 8) and 0xFF
      val b = colour and 0xFF
      if (abs(r - averageRed) <= SOLID_COLOR_TOLERANCE &&
        abs(g - averageGreen) <= SOLID_COLOR_TOLERANCE &&
        abs(b - averageBlue) <= SOLID_COLOR_TOLERANCE
      ) {
        similar++
      }
      // Strongest channel step to the right / down neighbour, so a coloured edge counts even when
      // the two sides happen to share a luminance.
      if (x + 1 < width) {
        val next = pixels[index + 1]
        if ((next ushr 24) and 0xFF >= 8) {
          edgeSum += maxChannelStep(colour, next)
          edgeCount++
        }
      }
      if (y + 1 < height) {
        val next = pixels[index + width]
        if ((next ushr 24) and 0xFF >= 8) {
          edgeSum += maxChannelStep(colour, next)
          edgeCount++
        }
      }
    }
  }

  return FrameFlatness(
    similarFraction = similar.toFloat() / size,
    meanEdge = if (edgeCount == 0) 0f else edgeSum.toFloat() / edgeCount,
  )
}

private fun maxChannelStep(
  first: Int,
  second: Int,
): Int =
  maxOf(
    abs(((first shr 16) and 0xFF) - ((second shr 16) and 0xFF)),
    abs(((first shr 8) and 0xFF) - ((second shr 8) and 0xFF)),
    abs((first and 0xFF) - (second and 0xFF)),
  )

/**
 * True when [bitmap] is a blank card (black lead-in, fade frame, static colour screen) rather than
 * a picture. Runs on a downscaled copy: structure survives the downscale while per-byte codec
 * noise does not, so dim scenes are no longer mistaken for empty frames.
 */
internal fun isMostlySolidThumbnail(
  bitmap: Bitmap,
  threshold: Float = SOLID_SIMILAR_FRACTION,
): Boolean {
  if (bitmap.isRecycled) return false
  val width = bitmap.width
  val height = bitmap.height
  if (width <= 0 || height <= 0) return false

  val sampleWidth = minOf(SOLID_SAMPLE_WIDTH, width)
  val sampleHeight = maxOf(1, (sampleWidth.toLong() * height / width).toInt())
  val scaled =
    if (sampleWidth == width && sampleHeight == height) {
      bitmap
    } else {
      Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
    }
  val pixels = IntArray(sampleWidth * sampleHeight)
  try {
    scaled.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
  } finally {
    if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
  }

  val flatness = measureFrameFlatness(pixels, sampleWidth, sampleHeight)
  val solid = flatness.similarFraction >= threshold && flatness.meanEdge <= SOLID_MAX_EDGE
  if (solid) {
    android.util.Log.d(
      "CloudThumbnail",
      "blank frame: similar=${"%.3f".format(flatness.similarFraction)} edge=${"%.2f".format(flatness.meanEdge)}",
    )
  }
  return solid
}
