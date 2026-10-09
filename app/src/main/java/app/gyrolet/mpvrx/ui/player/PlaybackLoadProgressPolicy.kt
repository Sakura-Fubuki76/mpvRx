package app.gyrolet.mpvrx.ui.player

/** A moving network probe is not a stalled load, but cannot defer failure indefinitely. */
internal fun canExtendPlaybackLoad(elapsedMs: Long, readAgeMs: Long?): Boolean =
  elapsedMs in 0 until 300_000L && readAgeMs != null && readAgeMs in 0..30_000L
