package app.gyrolet.mpvrx.domain.cloud

import app.gyrolet.mpvrx.database.entities.CloudFolderMetadataEntity
import app.gyrolet.mpvrx.domain.network.NetworkFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CloudFolderScannerTest {
  @Test fun countsNestedVideosAndPersistsCompleteEmptyFolders() = runBlocking {
    val saved = mutableMapOf<String, CloudFolderMetadataEntity>()
    val scanner = CloudFolderScanner(4, { path -> Result.success(when (path) {
      "/" -> listOf(NetworkFile("nested", "/nested", 0, true), NetworkFile("empty", "/empty", 0, true))
      "/nested" -> listOf(NetworkFile("movie.mkv", "/nested/movie.mkv", 100, false, durationMs = 5000))
      else -> emptyList()
    }) }, { saved[it.path] = it })
    val summary = scanner.scan("/")
    assertEquals(1, summary.videoCount)
    assertEquals(100L, summary.totalSize)
    assertEquals(5000L, summary.totalDurationMs)
    assertTrue(summary.scanComplete)
    assertEquals(0, saved["/empty"]!!.videoCount)
    assertTrue(saved["/empty"]!!.scanComplete)
  }

  @Test fun failureOrScanBudgetNeverProvesEmpty() = runBlocking {
    val scanner = CloudFolderScanner(1, { Result.failure(IllegalStateException("offline")) }, {})
    assertFalse(scanner.scan("/").scanComplete)
    val bounded = CloudFolderScanner(1, { Result.success(listOf(NetworkFile("sub", "/sub", 0, true))) }, {}, maxDirectories = 1)
    assertFalse(bounded.scan("/").scanComplete)
  }
}
