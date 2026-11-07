package app.gyrolet.mpvrx.ui.player

import kotlin.math.abs

/** Upstream's 0.5 s coalescing rule, shared by live scrubbing and the final seekbar release. */
internal fun shouldIssueScrubSeek(lastTarget: Double, target: Double): Boolean =
  target.isFinite() && (lastTarget.isNaN() || abs(target - lastTarget) >= 0.5)

/** Do not force exact seeking; only constrain the target to the media and active A-B loop. */
internal fun clampScrubTarget(position: Double, duration: Double, loopA: Double?, loopB: Double?): Double {
  val target = if (duration > 0.0) position.coerceIn(0.0, duration) else position.coerceAtLeast(0.0)
  return if (loopA != null && loopB != null) target.coerceIn(minOf(loopA, loopB), maxOf(loopA, loopB)) else target
}
