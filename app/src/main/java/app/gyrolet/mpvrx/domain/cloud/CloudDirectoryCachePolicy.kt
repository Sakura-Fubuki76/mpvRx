package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.database.entities.CloudFolderMetadataEntity
import app.gyrolet.mpvrx.domain.network.NetworkFile

/** Revalidation interval, not a lifetime for persisted listings or folder statistics. */
internal fun isCloudDirectoryFresh(scannedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
  scannedAt > 0 && now >= scannedAt && now - scannedAt < 30 * 60_000L

/** A completed snapshot stays displayable until an observed directory change invalidates it. */
internal fun NetworkFile.withCloudFolderSummary(summary: CloudFolderMetadataEntity?): NetworkFile =
  if (isDirectory && summary != null) copy(
    videoCount = summary.videoCount,
    size = summary.totalSize,
    durationMs = summary.totalDurationMs,
    folderScanComplete = summary.scanComplete,
  ) else this
