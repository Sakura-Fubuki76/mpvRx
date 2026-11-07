package app.gyrolet.mpvrx.ui.player.controls.components

import kotlin.math.abs

/** Upstream animates normal playback ticks, but snaps across seek/restore discontinuities. */
internal fun shouldSnapSeekbarPosition(position: Float, animatedPosition: Float): Boolean =
  abs(position - animatedPosition) > 2f

/** Keep the thumbnail and thumb on the same target without a separate native seek path. */
internal fun resolveSeekbarPosition(
  position: Float,
  animatedPosition: Float,
  userPosition: Float,
  isUserInteracting: Boolean,
  externalScrubbing: Boolean,
  previewPosition: Float?,
): Float = when {
  isUserInteracting -> userPosition
  previewPosition != null -> previewPosition
  externalScrubbing || shouldSnapSeekbarPosition(position, animatedPosition) -> position
  else -> animatedPosition
}
