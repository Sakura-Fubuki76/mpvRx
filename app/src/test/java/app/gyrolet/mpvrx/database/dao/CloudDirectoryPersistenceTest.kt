package app.gyrolet.mpvrx.database.dao

import app.gyrolet.mpvrx.database.entities.CloudDirectoryItemEntity
import app.gyrolet.mpvrx.database.entities.CloudDirectoryStateEntity
import app.gyrolet.mpvrx.database.entities.CloudFolderMetadataEntity
import app.gyrolet.mpvrx.database.entities.CloudVideoMetadataEntity
import app.gyrolet.mpvrx.domain.cloud.CloudFolderScanner
import app.gyrolet.mpvrx.domain.cloud.isCloudDirectoryFresh
import app.gyrolet.mpvrx.domain.cloud.withCloudFolderSummary
import app.gyrolet.mpvrx.domain.network.NetworkFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real DAO transaction bodies, with in-memory implementations of SQL primitives. */
class CloudDirectoryPersistenceTest {
  private val old = CloudFolderMetadataEntity(1, "/series", 360_000, 4096, 12, 1, true, 1_000_000)
  private val partial = old.copy(videoCount = 0, totalSize = 0, totalDurationMs = 0,
    scanComplete = false, updatedAt = old.updatedAt + 3 * 24 * 60 * 60_000L)
  private val child = CloudDirectoryItemEntity(1, "/series", "/series/season", "season", 0, 1, true, null)

  @Test fun unchangedListingOnlyRenewsDirectoryValidationTime() = runBlocking {
    val dao = MemoryDao()
    dao.putFolder(old)
    dao.insertItems(listOf(child))
    val refreshed = CloudDirectoryStateEntity(1, "/series", partial.updatedAt)
    dao.replaceDirectory(refreshed, listOf(child))
    assertEquals(old, dao.getFolder(1, "/series"))
    assertEquals(refreshed, dao.getDirectoryState(1, "/series"))
    assertEquals(listOf(child), dao.getDirectory(1, "/series"))
    assertTrue(dao.deletedPaths.isEmpty())
  }

  @Test fun failedPartialScanKeepsCompletenessCountsAndOldTimestamp() = runBlocking {
    val dao = MemoryDao()
    dao.putFolder(old)
    dao.putScannedFolder(partial)
    val retained = dao.getFolder(1, "/series")!!
    assertEquals(old, retained)
    assertFalse(isCloudDirectoryFresh(retained.updatedAt, partial.updatedAt))
    val displayed = NetworkFile("series", "/series", 0, true).withCloudFolderSummary(retained)
    assertTrue(displayed.folderScanComplete)
    assertEquals(12, displayed.videoCount)
  }

  @Test fun observedDeletionInvalidatesAncestorsAndFailureCannotUndoIt() = runBlocking {
    val dao = MemoryDao()
    dao.putFolder(old)
    dao.putFolder(old.copy(path = "/"))
    dao.putFolder(old.copy(connectionId = 2))
    dao.insertItems(listOf(child))
    dao.replaceDirectory(CloudDirectoryStateEntity(1, "/series", partial.updatedAt), emptyList())
    dao.putScannedFolder(partial)
    assertFalse(dao.getFolder(1, "/series")!!.scanComplete)
    assertEquals(0L, dao.getFolder(1, "/series")!!.updatedAt)
    assertFalse(dao.getFolder(1, "/")!!.scanComplete)
    assertTrue(dao.getFolder(2, "/series")!!.scanComplete)
    assertTrue(dao.getDirectory(1, "/series").isEmpty())
    assertTrue("/series/season" in dao.deletedPaths)
  }

  @Test fun completeEmptyScanReplacesTheOldNonemptySnapshot() = runBlocking {
    val dao = MemoryDao()
    dao.putFolder(old)
    val empty = partial.copy(scanComplete = true, folderCount = 0)
    dao.putScannedFolder(empty)
    assertEquals(empty, dao.getFolder(1, "/series"))
  }

  @Test fun firstPartialScanNeverClaimsACompleteEmptyFolder() = runBlocking {
    val dao = MemoryDao()
    dao.putScannedFolder(partial)
    assertEquals(partial, dao.getFolder(1, "/series"))
    assertFalse(dao.getFolder(1, "/series")!!.scanComplete)
  }

  @Test fun unreachableDescendantDoesNotDiscardTheLastCompleteParent() = runBlocking {
    val dao = MemoryDao()
    dao.putFolder(old)
    dao.insertItems(listOf(child))
    val scanner = CloudFolderScanner(1, { path ->
      if (path == "/series") {
        dao.replaceDirectory(CloudDirectoryStateEntity(1, path, partial.updatedAt), listOf(child))
        Result.success(listOf(NetworkFile("season", child.path, 0, true)))
      } else Result.failure(IllegalStateException("offline"))
    }, dao::putScannedFolder)
    assertFalse(scanner.scan("/series").scanComplete)
    assertEquals(old, dao.getFolder(1, "/series"))
  }

  private class MemoryDao : CloudMetadataDao() {
    private val folders = mutableMapOf<Pair<Long, String>, CloudFolderMetadataEntity>()
    private val items = mutableMapOf<Pair<Long, String>, CloudDirectoryItemEntity>()
    private val states = mutableMapOf<Pair<Long, String>, CloudDirectoryStateEntity>()
    val deletedPaths = mutableListOf<String>()

    override suspend fun getFolder(connectionId: Long, path: String) = folders[connectionId to path]
    override suspend fun putFolder(folder: CloudFolderMetadataEntity) { folders[folder.connectionId to folder.path] = folder }
    override suspend fun getDirectory(connectionId: Long, path: String) =
      items.values.filter { it.connectionId == connectionId && it.parentPath == path }
    override suspend fun insertItems(items: List<CloudDirectoryItemEntity>) {
      items.forEach { this.items[it.connectionId to it.path] = it }
    }
    override suspend fun deleteDirectory(connectionId: Long, path: String) {
      items.entries.removeAll { it.value.connectionId == connectionId && it.value.parentPath == path }
    }
    override suspend fun getDirectoryState(connectionId: Long, path: String) = states[connectionId to path]
    override suspend fun putState(state: CloudDirectoryStateEntity) { states[state.connectionId to state.path] = state }
    override suspend fun invalidateFolderAncestors(id: Long, path: String) {
      folders.replaceAll { _, row ->
        if (row.connectionId == id && (row.path == "/" || row.path == path || path.startsWith(row.path + "/")))
          row.copy(scanComplete = false, updatedAt = 0) else row
      }
    }
    private fun isBelow(candidate: String, path: String) = candidate == path || candidate.startsWith(path + "/")
    override suspend fun deleteVideoSubtree(id: Long, path: String) { deletedPaths.add(path) }
    override suspend fun deleteItemSubtree(id: Long, path: String) {
      items.entries.removeAll { it.value.connectionId == id && isBelow(it.value.path, path) }
    }
    override suspend fun deleteStateSubtree(id: Long, path: String) {
      states.entries.removeAll { it.value.connectionId == id && isBelow(it.value.path, path) }
    }
    override suspend fun deleteFolderSubtree(id: Long, path: String) {
      folders.entries.removeAll { it.value.connectionId == id && isBelow(it.value.path, path) }
    }
    override suspend fun refreshFolderDurations(connectionId: Long, path: String?, includeAncestors: Boolean) = Unit
    override suspend fun getVideo(connectionId: Long, path: String): CloudVideoMetadataEntity? = error("Not used by these tests")
    override fun observeVideos(connectionId: Long, paths: List<String>): Flow<List<CloudVideoMetadataEntity>> = error("Not used by these tests")
    override suspend fun getVideos(connectionId: Long, paths: List<String>): List<CloudVideoMetadataEntity> = error("Not used by these tests")
    override suspend fun mergeVideo(
        connectionId: Long, path: String, size: Long, lastModified: Long,
        durationMs: Long, width: Int, height: Int, updatedAt: Long, fps: Float, videoCodec: String, hasEmbeddedSubtitles: Boolean, subtitleCodec: String, technicalVersion: Int,
      ) = error("Not used by these tests")
    override suspend fun getItem(connectionId: Long, path: String): CloudDirectoryItemEntity? = error("Not used by these tests")
    override suspend fun getFilesBelow(connectionId: Long, path: String): List<CloudDirectoryItemEntity> = error("Not used by these tests")
    override fun observeLibrary(connectionId: Long, path: String, videosOnly: Boolean): Flow<List<app.gyrolet.mpvrx.database.entities.CloudLibraryItem>> = error("Not used by these tests")
    override fun observeFolders(connectionId: Long): Flow<List<CloudFolderMetadataEntity>> = error("Not used by these tests")
    override suspend fun deleteVideos(connectionId: Long) = error("Not used by these tests")
    override suspend fun deleteItems(connectionId: Long) = error("Not used by these tests")
    override suspend fun deleteStates(connectionId: Long) = error("Not used by these tests")
    override suspend fun deleteFolders(connectionId: Long) = error("Not used by these tests")
  }
}
