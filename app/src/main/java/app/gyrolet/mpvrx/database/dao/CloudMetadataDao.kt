package app.gyrolet.mpvrx.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.gyrolet.mpvrx.database.entities.CloudDirectoryItemEntity
import app.gyrolet.mpvrx.database.entities.CloudDirectoryStateEntity
import app.gyrolet.mpvrx.database.entities.CloudFolderMetadataEntity
import app.gyrolet.mpvrx.database.entities.CloudVideoMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CloudMetadataDao {
  @Query("SELECT * FROM cloud_video_metadata WHERE connectionId = :connectionId AND path = :path")
  abstract suspend fun getVideo(connectionId: Long, path: String): CloudVideoMetadataEntity?

  @Query("SELECT * FROM cloud_video_metadata WHERE connectionId = :connectionId AND path IN (:paths)")
  abstract fun observeVideos(connectionId: Long, paths: List<String>): Flow<List<CloudVideoMetadataEntity>>

  @Query("SELECT * FROM cloud_video_metadata WHERE connectionId = :connectionId AND path IN (:paths)")
  abstract suspend fun getVideos(connectionId: Long, paths: List<String>): List<CloudVideoMetadataEntity>

  // Merge is atomic, and a new file version resets fields from the old file.
  @Query("""INSERT OR REPLACE INTO cloud_video_metadata(connectionId, path, size, lastModified, durationMs, width, height, updatedAt, fps, videoCodec, hasEmbeddedSubtitles, subtitleCodec, technicalVersion)
    VALUES (:connectionId, :path, :size, :lastModified,
      CASE WHEN :durationMs > 0 THEN :durationMs ELSE COALESCE((SELECT durationMs FROM cloud_video_metadata
        WHERE connectionId = :connectionId AND path = :path AND size = :size AND lastModified = :lastModified), 0) END,
      CASE WHEN :width > 0 THEN :width ELSE COALESCE((SELECT width FROM cloud_video_metadata
        WHERE connectionId = :connectionId AND path = :path AND size = :size AND lastModified = :lastModified), 0) END,
      CASE WHEN :height > 0 THEN :height ELSE COALESCE((SELECT height FROM cloud_video_metadata
        WHERE connectionId = :connectionId AND path = :path AND size = :size AND lastModified = :lastModified), 0) END,
      :updatedAt,
      CASE WHEN :fps > 0 THEN :fps ELSE COALESCE((SELECT fps FROM cloud_video_metadata WHERE connectionId = :connectionId AND path = :path AND size = :size AND lastModified = :lastModified), 0) END,
      CASE WHEN :videoCodec != '' THEN :videoCodec ELSE COALESCE((SELECT videoCodec FROM cloud_video_metadata WHERE connectionId = :connectionId AND path = :path AND size = :size AND lastModified = :lastModified), '') END,
      CASE WHEN :hasEmbeddedSubtitles THEN 1 ELSE COALESCE((SELECT hasEmbeddedSubtitles FROM cloud_video_metadata WHERE connectionId = :connectionId AND path = :path AND size = :size AND lastModified = :lastModified), 0) END,
      CASE WHEN :subtitleCodec != '' THEN :subtitleCodec ELSE COALESCE((SELECT subtitleCodec FROM cloud_video_metadata WHERE connectionId = :connectionId AND path = :path AND size = :size AND lastModified = :lastModified), '') END,
      MAX(:technicalVersion, COALESCE((SELECT technicalVersion FROM cloud_video_metadata WHERE connectionId = :connectionId AND path = :path AND size = :size AND lastModified = :lastModified), 0)))""")
  abstract suspend fun mergeVideo(
    connectionId: Long, path: String, size: Long, lastModified: Long,
    durationMs: Long, width: Int, height: Int, updatedAt: Long, fps: Float = 0f, videoCodec: String = "", hasEmbeddedSubtitles: Boolean = false, subtitleCodec: String = "", technicalVersion: Int = 0,
  )

  @Query("SELECT * FROM cloud_directory_items WHERE connectionId = :connectionId AND path = :path LIMIT 1")
  abstract suspend fun getItem(connectionId: Long, path: String): CloudDirectoryItemEntity?

  @Query("""SELECT * FROM cloud_directory_items WHERE connectionId = :connectionId AND isDirectory = 0
    AND (:path = '/' OR substr(path, 1, length(:path) + 1) = :path || '/')
    ORDER BY CASE WHEN parentPath = :path THEN 0 ELSE 1 END, path""")
  abstract suspend fun getFilesBelow(connectionId: Long, path: String): List<CloudDirectoryItemEntity>

  @Transaction
  open suspend fun mergeCurrentVideo(connectionId: Long, path: String, size: Long, modified: Long,
    duration: Long, width: Int, height: Int, updatedAt: Long, fps: Float = 0f, videoCodec: String = "", hasEmbeddedSubtitles: Boolean = false, subtitleCodec: String = "", technicalVersion: Int = 0) {
    val current = getItem(connectionId, path)
    if (current == null || current.size != size || current.lastModified != modified) {
      app.gyrolet.mpvrx.domain.cloud.CloudTrace.event("metadata.reject", connectionId, path,
        "reason=${if (current == null) "missing_item" else "file_version"}")
      return
    }
    val previous = getVideo(connectionId, path)
    if (previous != null && previous.size == size && previous.lastModified == modified && previous.durationMs > 0 &&
      (duration <= 0 || duration == previous.durationMs) && (width <= 0 || width == previous.width) &&
      (height <= 0 || height == previous.height) && (fps <= 0 || fps == previous.fps) &&
      (videoCodec.isEmpty() || videoCodec == previous.videoCodec) &&
      (subtitleCodec.isEmpty() || subtitleCodec == previous.subtitleCodec) && technicalVersion <= previous.technicalVersion &&
      (!hasEmbeddedSubtitles || previous.hasEmbeddedSubtitles)) return
    mergeVideo(connectionId, path, size, modified, duration, width, height, updatedAt, fps, videoCodec, hasEmbeddedSubtitles, subtitleCodec, technicalVersion)
  }

  @Query("""SELECT d.*, COALESCE(v.durationMs, 0) AS durationMs,
    COALESCE(v.width, 0) AS width, COALESCE(v.height, 0) AS height, COALESCE(v.fps, 0) AS fps,
    COALESCE(v.videoCodec, '') AS videoCodec, COALESCE(v.hasEmbeddedSubtitles, 0) AS hasEmbeddedSubtitles,
    COALESCE(v.subtitleCodec, '') AS subtitleCodec
    FROM cloud_directory_items d LEFT JOIN cloud_video_metadata v
    ON d.connectionId = v.connectionId AND d.path = v.path
      AND d.size = v.size AND d.lastModified = v.lastModified
    WHERE d.connectionId = :connectionId
      AND (:path = '/' OR substr(d.path, 1, length(:path) + 1) = :path || '/')
      AND (:videosOnly = 0 OR (d.isDirectory = 0 AND (d.mimeType LIKE 'video/%'
        OR lower(substr(d.name, -4)) IN ('.mp4','.mkv','.avi','.mov','.wmv','.flv','.m4v','.3gp','.3g2','.mpg','.m2v','.ogv','.mts','.vob','.f4v','.asf')
        OR lower(substr(d.name, -5)) IN ('.webm','.mpeg','.m2ts','.divx','.xvid','.rmvb')
        OR lower(substr(d.name, -3)) IN ('.ts','.rm'))))
    ORDER BY d.path""")
  abstract fun observeLibrary(connectionId: Long, path: String, videosOnly: Boolean = false): Flow<List<app.gyrolet.mpvrx.database.entities.CloudLibraryItem>>

  @Query("SELECT * FROM cloud_directory_state WHERE connectionId = :connectionId AND path = :path")
  abstract suspend fun getDirectoryState(connectionId: Long, path: String): CloudDirectoryStateEntity?

  @Query("SELECT * FROM cloud_directory_items WHERE connectionId = :connectionId AND parentPath = :path ORDER BY isDirectory DESC, name COLLATE NOCASE")
  abstract suspend fun getDirectory(connectionId: Long, path: String): List<CloudDirectoryItemEntity>

  @Query("DELETE FROM cloud_directory_items WHERE connectionId = :connectionId AND parentPath = :path")
  abstract suspend fun deleteDirectory(connectionId: Long, path: String)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun insertItems(items: List<CloudDirectoryItemEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun putState(state: CloudDirectoryStateEntity)

  @Transaction
  open suspend fun replaceDirectory(state: CloudDirectoryStateEntity, items: List<CloudDirectoryItemEntity>) {
    val retained = items.map { it.path }.toSet()
    getDirectory(state.connectionId, state.path).filter { it.path !in retained }.forEach {
      deleteVideoSubtree(state.connectionId, it.path)
      deleteItemSubtree(state.connectionId, it.path)
      deleteStateSubtree(state.connectionId, it.path)
      deleteFolderSubtree(state.connectionId, it.path)
    }
    deleteDirectory(state.connectionId, state.path)
    insertItems(items)
    putState(state)
  }

  @Query("DELETE FROM cloud_video_metadata WHERE connectionId = :id AND (path = :path OR substr(path, 1, length(:path) + 1) = :path || '/')")
  abstract suspend fun deleteVideoSubtree(id: Long, path: String)
  @Query("DELETE FROM cloud_directory_items WHERE connectionId = :id AND (path = :path OR substr(path, 1, length(:path) + 1) = :path || '/')")
  abstract suspend fun deleteItemSubtree(id: Long, path: String)
  @Query("DELETE FROM cloud_directory_state WHERE connectionId = :id AND (path = :path OR substr(path, 1, length(:path) + 1) = :path || '/')")
  abstract suspend fun deleteStateSubtree(id: Long, path: String)
  @Query("DELETE FROM cloud_folder_metadata WHERE connectionId = :id AND (path = :path OR substr(path, 1, length(:path) + 1) = :path || '/')")
  abstract suspend fun deleteFolderSubtree(id: Long, path: String)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun putFolder(folder: CloudFolderMetadataEntity)

  @Query("SELECT * FROM cloud_folder_metadata WHERE connectionId = :connectionId")
  abstract fun observeFolders(connectionId: Long): Flow<List<CloudFolderMetadataEntity>>

  @Query("SELECT * FROM cloud_folder_metadata WHERE connectionId = :connectionId AND path = :path")
  abstract suspend fun getFolder(connectionId: Long, path: String): CloudFolderMetadataEntity?

  @Transaction
  open suspend fun putScannedFolder(folder: CloudFolderMetadataEntity) {
    val previous = getFolder(folder.connectionId, folder.path)
    val retained = if (!folder.scanComplete && previous != null) folder.copy(
      videoCount = previous.videoCount, totalSize = previous.totalSize,
      totalDurationMs = previous.totalDurationMs, folderCount = previous.folderCount,
    ) else folder
    putFolder(retained)
    // List responses contain no durations. Publish the persisted sum in the same transaction.
    refreshFolderDurations(folder.connectionId, folder.path)
  }

  @Query("""UPDATE cloud_folder_metadata SET totalDurationMs = COALESCE((
    SELECT SUM(v.durationMs) FROM cloud_video_metadata v WHERE v.connectionId = :connectionId
    AND (cloud_folder_metadata.path = '/' OR substr(v.path, 1, length(cloud_folder_metadata.path) + 1) = cloud_folder_metadata.path || '/')
    AND EXISTS(SELECT 1 FROM cloud_directory_items i WHERE i.connectionId = v.connectionId AND i.path = v.path
      AND i.size = v.size AND i.lastModified = v.lastModified)), 0) WHERE connectionId = :connectionId
    AND (:path IS NULL OR cloud_folder_metadata.path = :path OR (:includeAncestors AND
      (cloud_folder_metadata.path = '/' OR substr(:path, 1, length(cloud_folder_metadata.path) + 1) = cloud_folder_metadata.path || '/')))""")
  abstract suspend fun refreshFolderDurations(connectionId: Long, path: String? = null, includeAncestors: Boolean = false)

  @Query("DELETE FROM cloud_video_metadata WHERE connectionId = :connectionId")
  abstract suspend fun deleteVideos(connectionId: Long)

  @Query("DELETE FROM cloud_directory_items WHERE connectionId = :connectionId")
  abstract suspend fun deleteItems(connectionId: Long)

  @Query("DELETE FROM cloud_directory_state WHERE connectionId = :connectionId")
  abstract suspend fun deleteStates(connectionId: Long)

  @Query("DELETE FROM cloud_folder_metadata WHERE connectionId = :connectionId")
  abstract suspend fun deleteFolders(connectionId: Long)

  @Transaction
  open suspend fun invalidateConnection(connectionId: Long) {
    deleteVideos(connectionId)
    deleteItems(connectionId)
    deleteStates(connectionId)
    deleteFolders(connectionId)
  }
}
