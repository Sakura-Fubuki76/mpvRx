/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.cloud

import android.graphics.Bitmap
import android.graphics.Matrix
import app.gyrolet.mpvrx.domain.thumbnail.isMostlySolidThumbnail
import okhttp3.OkHttpClient
import kotlin.math.abs
import kotlinx.coroutines.ensureActive

/**
 * Index-based frame extraction for remote MP4/MKV sources.
 *
 * This is the ported core of yume's "advanced thumbnail download": instead of streaming the whole
 * file through [android.media.MediaMetadataRetriever], the container is probed with a handful of
 * HTTP range requests, the keyframe table is parsed, and only the bytes of one keyframe are
 * downloaded before being decoded with [android.media.MediaCodec]. For a two-hour movie that is a
 * few hundred kilobytes instead of the entire file.
 *
 * Both the parsed index and the resulting thumbnail are cached, so a second visit costs nothing.
 * Callers supply [stableKey] because the remote bytes may be reached through a loopback proxy whose
 * port and token change on every registration.
 */
class CloudKeyframeExtractor(
  okHttpClient: OkHttpClient,
) {
  private val mp4 = Mp4KeyframeExtractor(okHttpClient)
  private val mkv = MkvKeyframeExtractor(okHttpClient)

  data class Result(
    val bitmap: Bitmap,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
  )

  /**
   * Whether [extension] is a container this extractor can index. MP4 handles ISO-BMFF; the MKV
   * path handles Matroska/WebM through its cue table.
   */
  fun supports(extension: String): Boolean =
    extension.lowercase() in MP4_EXTENSIONS ||
      extension.lowercase() in MKV_EXTENSIONS

  /**
   * Extracts a keyframe at [targetPercent] of the runtime.
   *
   * @param streamUrl URL the extractor may issue range requests against.
   * @param stableKey durable cache identity (never contains a token or temporary URL).
   * @param extension file extension without the dot, used to pick the container parser.
   * @param targetPercent position in `0f..1f`.
   * @param solidFallbackPercent when non-null, a frame that is almost entirely one colour — the
   *   classic "black frame at 0:00" case — is retried at this position instead. Mirrors yume's
   *   HYBRID strategy.
   */
  suspend fun extract(
    streamUrl: String,
    stableKey: String,
    extension: String,
    targetPercent: Float,
    solidFallbackPercent: Float? = null,
  ): Result? {
    val ext = extension.lowercase()
    val primary = extractAt(streamUrl, stableKey, ext, targetPercent) ?: return null
    if (solidFallbackPercent == null) return primary

    // The near-solid retry only earns its extra range requests when the first frame really is
    // flat; a normal frame is returned as-is.
    if (!isMostlySolidThumbnail(primary.bitmap)) return primary

    val retry = extractAt(streamUrl, stableKey, ext, solidFallbackPercent) ?: return primary
    primary.bitmap.recycle()
    return retry
  }

  private suspend fun extractAt(
    url: String,
    stableKey: String,
    extension: String,
    percent: Float,
  ): Result? =
    when (extension) {
      in MP4_EXTENSIONS ->
        mp4.extractKeyframeWithMetadata(url, percent, stableKey)?.let {
          val info = mp4.loadParsedMoov(url, stableKey)?.moovInfo
          val bitmap = if (info?.rotation != null && info.rotation != 0) {
            Bitmap.createBitmap(it.bitmap, 0, 0, it.bitmap.width, it.bitmap.height,
              Matrix().apply { postRotate(info.rotation.toFloat()) }, true).also { rotated ->
              if (rotated !== it.bitmap) it.bitmap.recycle()
            }
          } else it.bitmap
          Result(bitmap, it.durationMs, it.width, it.height)
        }
      in MKV_EXTENSIONS -> extractMkvKeyframe(url, stableKey, percent)
      else -> null
    }

  private suspend fun extractMkvKeyframe(
    url: String,
    stableKey: String,
    percent: Float,
  ): Result? {
    val parsed = mkv.loadParsedMkv(url, stableKey) ?: return null
    val info = parsed.moovInfo ?: return null
    if (info.keyframes.isEmpty()) return null

    val durationMs = parsed.durationMs ?: info.durationMsFromTimescale() ?: 0L
    val targetMs = (durationMs * percent.coerceIn(0f, 1f)).toLong()
    val keyframe = info.keyframes.minByOrNull { abs(it.timeMs - targetMs) } ?: return null

    val cluster =
      mkv.downloadMkvKeyframe(
        url = url,
        clusterPos = keyframe.byteOffset,
        estimatedSize = keyframe.byteSize,
        trackNumber = parsed.videoTrackNumber,
      ) ?: return null

    val bitmap = mp4.decodeKeyframe(info, cluster) ?: return null
    return Result(
      bitmap = bitmap,
      durationMs = durationMs.takeIf { it > 0L },
      width = info.width.takeIf { it > 0 },
      height = info.height.takeIf { it > 0 },
    )
  }

  /** yume sprite path: bounded download batch, one decoder, YUV 320x180 -> 160x90 before RGB. */
  suspend fun spriteBatch(url: String, stableKey: String, extension: String, targets: List<Long>): Map<Long, Bitmap> {
    if (!YuvToBitmapBridge.available || targets.isEmpty()) return emptyMap()
    val parsed = if (extension.lowercase() in MKV_EXTENSIONS) mkv.loadParsedMkv(url, stableKey)
      else mp4.loadParsedMoov(url, stableKey)
    val info = parsed?.moovInfo ?: return emptyMap()
    val data = mutableListOf<Pair<Long, ByteArray>>()
    val result = linkedMapOf<Long, Bitmap>()
    val batchContext = kotlinx.coroutines.currentCoroutineContext()
    try {
      for (target in targets) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val keyframe = info.keyframes.minByOrNull { abs(it.timeMs - target) } ?: continue
        val bytes = if (extension.lowercase() in MKV_EXTENSIONS)
          mkv.downloadMkvKeyframe(url, keyframe.byteOffset, keyframe.byteSize, parsed.videoTrackNumber)
        else mp4.httpRange(url, keyframe.byteOffset, keyframe.byteSize)
        if (bytes != null) data += target to bytes
      }
      mp4.decodeKeyframesRawImage(info, data.map { it.second }) { image, format, codec, index ->
        batchContext.ensureActive()
        val crop = android.graphics.Rect(image.cropRect)
        val right = if (format.containsKey("crop-right")) format.getInteger("crop-right") + 1 else info.width
        val bottom = if (format.containsKey("crop-bottom")) format.getInteger("crop-bottom") + 1 else info.height
        if (crop.intersect(0, 0, minOf(image.width, right), minOf(image.height, bottom))) {
          image.cropRect = crop
          val scaled = YuvToBitmapBridge.scaleTwoPassFromImage(image, 320, 180, 160, 90)
          CloudTrace.event("sprite.decode", detail = "codec=$codec image=${image.width}x${image.height} crop=${crop.width()}x${crop.height()} strides=${image.planes.joinToString(",") { "${it.rowStride}/${it.pixelStride}" }} native=${scaled != null} bytes=${data[index].second.size}")
          if (scaled != null) {
            val standard = if (format.containsKey(android.media.MediaFormat.KEY_COLOR_STANDARD)) format.getInteger(android.media.MediaFormat.KEY_COLOR_STANDARD) else 1
            val range = if (format.containsKey(android.media.MediaFormat.KEY_COLOR_RANGE)) format.getInteger(android.media.MediaFormat.KEY_COLOR_RANGE) else 2
            CloudTrace.event("sprite.color", detail = "standard=$standard range=$range output=160x90 filter=BOX")
            val bitmap = YuvToBitmapBridge.imageToBitmap(scaled.y, scaled.strideY, 1,
              scaled.u, scaled.strideU, 1, scaled.v, scaled.strideV, 1, 0, 0, 160, 90, standard, range, false)
            if (bitmap != null) result[data[index].first] = bitmap
          }
        }
      }
      CloudTrace.event("sprite.batch", detail = "requested=${targets.size} downloaded=${data.size} decoded=${result.size}")
      return result
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      result.values.forEach { it.recycle() }
      throw cancelled
    } catch (error: Exception) {
      CloudTrace.event("sprite.batch.failed", detail = "error=${error.javaClass.simpleName}")
      return result
    }
  }

  /** Simple duration probe that reuses the parsed index; returns null when the container is unknown. */
  suspend fun extractDurationMs(
    url: String,
    stableKey: String,
    extension: String,
  ): Long? =
    when (extension.lowercase()) {
      in MP4_EXTENSIONS -> mp4.extractDurationMs(url, stableKey)
      in MKV_EXTENSIONS -> mkv.loadParsedMkv(url, stableKey)?.durationMs
      else -> null
    }

  suspend fun frameTimes(url: String, key: String, extension: String, duration: Long): List<Long> {
    val parsed = when (extension.lowercase()) {
      in MP4_EXTENSIONS -> mp4.loadParsedMoov(url, key)
      in MKV_EXTENSIONS -> mkv.loadParsedMkv(url, key)
      else -> null
    }
    val times = parsed?.moovInfo?.keyframes?.map { it.timeMs }?.filter { it in 0..duration }?.distinct()?.sorted().orEmpty()
    if (times.size <= 100) return times
    return (0 until 100).map { index ->
      val target = duration * index / 100
      times.minBy { abs(it - target) }
    }.distinct()
  }

  private companion object {
    val MP4_EXTENSIONS = setOf("mp4", "mov", "m4v")
    val MKV_EXTENSIONS = setOf("mkv", "webm")
  }
}

/**
 * Converts a media timescale/duration pair into milliseconds. The extractor keeps this private, so
 * the wrapper repeats the two-line conversion rather than widening that class's API.
 */
private fun Mp4KeyframeExtractor.MoovInfo.durationMsFromTimescale(): Long? =
  if (timescale > 0 && duration > 0L) (duration * 1000L / timescale).takeIf { it > 0L } else null
