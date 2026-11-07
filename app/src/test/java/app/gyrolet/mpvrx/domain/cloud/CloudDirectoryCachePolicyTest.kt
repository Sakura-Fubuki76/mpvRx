package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.database.entities.CloudFolderMetadataEntity
import app.gyrolet.mpvrx.domain.network.NetworkFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDirectoryCachePolicyTest {
  @Test fun persistedTimestampSurvivesRestartWithoutRenewingAge() {
    val scannedAt = 1_000_000L
    assertTrue(isCloudDirectoryFresh(scannedAt, scannedAt + 29 * 60_000L))
    assertFalse(isCloudDirectoryFresh(scannedAt, scannedAt + 30 * 60_000L))
  }

  @Test fun missingOrFutureTimestampRequiresRefresh() {
    assertFalse(isCloudDirectoryFresh(0, 1_000_000L))
    assertFalse(isCloudDirectoryFresh(-1, 1_000_000L))
    assertFalse(isCloudDirectoryFresh(1_000_001L, 1_000_000L))
  }
  private val folder = NetworkFile("series", "/series", 0, true)
  private val summary = CloudFolderMetadataEntity(1, "/series", 360_000, 4096, 12, 2, true, 1_000_000)

  @Test fun daysOldSummaryIsDisplayableEvenThoughRevalidationIsDue() {
    val now = System.currentTimeMillis()
    for (ageInDays in listOf(1L, 3L, 30L)) {
      val persisted = summary.copy(updatedAt = now - ageInDays * 24 * 60 * 60_000L)
      assertFalse(isCloudDirectoryFresh(persisted.updatedAt, now))
      val displayed = folder.withCloudFolderSummary(persisted)
      assertTrue(displayed.folderScanComplete)
      assertEquals(12, displayed.videoCount)
      assertEquals(4096L, displayed.size)
      assertEquals(360_000L, displayed.durationMs)
      assertEquals("/series", displayed.path)
    }
  }

  @Test fun lastKnownEmptyFolderDoesNotBecomeUnknownWithAge() {
    val empty = summary.copy(videoCount = 0, totalDurationMs = 0, totalSize = 0)
    val displayed = folder.withCloudFolderSummary(empty)
    assertTrue(displayed.folderScanComplete)
    assertEquals(0, displayed.videoCount)
  }

  @Test fun knownChangesStillInvalidateCompleteness() {
    val invalidated = summary.copy(scanComplete = false, updatedAt = 0)
    val displayed = folder.withCloudFolderSummary(invalidated)
    assertFalse(displayed.folderScanComplete)
    assertEquals(12, displayed.videoCount)
  }

  @Test fun missingSummaryDoesNotPretendTheFolderIsEmpty() {
    assertSame(folder, folder.withCloudFolderSummary(null))
    assertFalse(folder.folderScanComplete)
  }

  @Test fun folderSummaryNeverReplacesVideoMetadata() {
    val video = folder.copy(isDirectory = false, size = 99, durationMs = 200)
    assertSame(video, video.withCloudFolderSummary(summary))
  }
}
