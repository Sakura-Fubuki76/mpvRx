package app.gyrolet.mpvrx.database.entities

import androidx.room.Entity
import androidx.room.Index

/** Persistent identity never contains authentication or a temporary playback URL. */
@Entity(tableName = "cloud_video_metadata", primaryKeys = ["connectionId", "path"])
data class CloudVideoMetadataEntity(
  val connectionId: Long,
  val path: String,
  val size: Long,
  val lastModified: Long,
  val durationMs: Long,
  val width: Int,
  val height: Int,
  val updatedAt: Long,
)

@Entity(
  tableName = "cloud_directory_items",
  primaryKeys = ["connectionId", "parentPath", "path"],
  indices = [Index(value = ["connectionId", "parentPath"])],
)
data class CloudDirectoryItemEntity(
  val connectionId: Long,
  val parentPath: String,
  val path: String,
  val name: String,
  val size: Long,
  val lastModified: Long,
  val isDirectory: Boolean,
  val mimeType: String?,
)

/** Separate state makes an empty directory a valid cache hit. */
@Entity(tableName = "cloud_directory_state", primaryKeys = ["connectionId", "path"])
data class CloudDirectoryStateEntity(
  val connectionId: Long,
  val path: String,
  val scannedAt: Long,
)

/** A summary is explicitly marked complete before it may prove a folder contains no video. */
@Entity(tableName = "cloud_folder_metadata", primaryKeys = ["connectionId", "path"])
data class CloudFolderMetadataEntity(
  val connectionId: Long,
  val path: String,
  val totalDurationMs: Long,
  val totalSize: Long,
  val videoCount: Int,
  val folderCount: Int,
  val scanComplete: Boolean,
  val updatedAt: Long,
)

/** A read-only projection; no additional table or database migration is required. */
data class CloudLibraryItem(
  @androidx.room.Embedded val item: CloudDirectoryItemEntity,
  val durationMs: Long,
  val width: Int,
  val height: Int,
)
