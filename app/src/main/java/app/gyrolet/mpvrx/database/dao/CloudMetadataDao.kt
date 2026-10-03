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

  // Merge is atomic, and a new file version resets fields from the old file.
  @Query("""INSERT OR REPLACE INTO cloud_video_metadata(connectionId, path, size, lastModified, durationMs, width, height, updatedAt)
    VALUES (:connectionId, :path, :size, :lastModified,
      CASE WHEN :durationMs > 0 THEN :durationMs ELSE COALESCE((SELECT durationMs FROM cloud_video_metadata
        WHERE connectionId = :connectionId AND path = :path AND size = :size AND lastModified = :lastModified), 0) END,
      CASE WHEN :width > 0 THEN :width ELSE COALESCE((SELECT width FROM cloud_video_metadata
        WHERE connectionId = :connectionId AND path = :path AND size = :size AND lastModified = :lastModified), 0) END,
      CASE WHEN :height > 0 THEN :height ELSE COALESCE((SELECT height FROM cloud_video_metadata
        WHERE connectionId = :connectionId AND path = :path AND size = :size AND lastModified = :lastModified), 0) END,
      :updatedAt)""")
  abstract suspend fun mergeVideo(
    connectionId: Long, path: String, size: Long, lastModified: Long,
    durationMs: Long, width: Int, height: Int, updatedAt: Long,
  )

  @Query("SELECT * FROM cloud_directory_items WHERE connectionId = :connectionId AND path = :path LIMIT 1")
  abstract suspend fun getItem(connectionId: Long, path: String): CloudDirectoryItemEntity?

  @Transaction
  open suspend fun mergeCurrentVideo(connectionId: Long, path: String, size: Long, modified: Long,
    duration: Long, width: Int, height: Int, updatedAt: Long) {
    val current = getItem(connectionId, path) ?: return
    if (current.size != size || current.lastModified != modified) return
    mergeVideo(connectionId, path, size, modified, duration, width, height, updatedAt)
  }

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

  @Query("""UPDATE cloud_folder_metadata SET totalDurationMs = COALESCE((
    SELECT SUM(v.durationMs) FROM cloud_video_metadata v WHERE v.connectionId = :connectionId
    AND (cloud_folder_metadata.path = '/' OR substr(v.path, 1, length(cloud_folder_metadata.path) + 1) = cloud_folder_metadata.path || '/')
    AND EXISTS(SELECT 1 FROM cloud_directory_items i WHERE i.connectionId = v.connectionId AND i.path = v.path
      AND i.size = v.size AND i.lastModified = v.lastModified)), 0) WHERE connectionId = :connectionId""")
  abstract suspend fun refreshFolderDurations(connectionId: Long)

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
