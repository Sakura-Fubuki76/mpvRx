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
    if (isMostlySolidThumbnail(retry.bitmap)) {
      retry.bitmap.recycle()
      return primary
    }
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
