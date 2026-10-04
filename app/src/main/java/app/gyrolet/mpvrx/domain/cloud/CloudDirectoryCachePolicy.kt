package app.gyrolet.mpvrx.domain.cloud

/** Persisted listing age, independent of process lifetime and metadata completion. */
internal fun isCloudDirectoryFresh(scannedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
  scannedAt > 0 && now >= scannedAt && now - scannedAt < 30 * 60_000L
