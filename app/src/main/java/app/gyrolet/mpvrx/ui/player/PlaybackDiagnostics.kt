package app.gyrolet.mpvrx.ui.player

import android.util.Log

/**
 * Temporary, verbose playback diagnostics used while tracking down decode/seek issues on real
 * devices. Everything funnels through [PlaybackSession]'s live mpv properties so a logcat capture
 * answers "which decoder did this file actually use, and on which backend".
 */
internal object PlaybackDiagnostics {
  private const val TAG = "MpvDiag"

  fun logSnapshot(
    stage: String,
    item: PlaybackItem? = null,
    extra: String = "",
  ) {
    val source = item?.let { sourceKind(it) } ?: "none"
    val values =
      listOf(
        "hwdec",
        "hwdec-current",
        "hwdec-interop",
        "video-params/pixelformat",
        "video-params/hw-pixelformat",
        "video-params/w",
        "video-params/h",
        "video-params/primaries",
        "current-tracks/video/codec",
        "current-tracks/video/codec-desc",
        "current-tracks/video/hw-w",
        "current-tracks/video/hw-h",
        "file-format",
        "demuxer",
        "vo-configured",
        "gpu-api",
        "gpu-context",
        "video-out-params/current-vo",
        "duration",
        "container-fps",
        "vd-lavc-threads",
      ).joinToString(" ") { property ->
        "$property=${runCatching { PlaybackSession.getPropertyString(property) }.getOrNull() ?: "-"}"
      }
    Log.i(TAG, "stage=$stage source=$source $extra $values")
  }

  fun sourceKind(item: PlaybackItem): String =
    when {
      item.networkSource != null -> "network:${item.networkSource.connectionId}"
      item.originalUri.startsWith("content://") -> "content"
      item.originalUri.startsWith("http://", true) || item.originalUri.startsWith("https://", true) -> "http"
      item.originalUri.startsWith("file://") -> "file-uri"
      else -> "path"
    }
}
