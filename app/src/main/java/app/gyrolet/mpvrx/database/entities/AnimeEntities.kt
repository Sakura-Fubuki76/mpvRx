package app.gyrolet.mpvrx.database.entities

import androidx.room.Entity

/** Public Bangumi metadata is shared; folder associations remain storage-specific. */
@Entity(tableName = "anime_subjects", primaryKeys = ["id"])
data class AnimeSubjectEntity(val id: Long, val payload: String, val fetchedAt: Long)

@Entity(tableName = "anime_folders", primaryKeys = ["connectionId", "path"])
data class AnimeFolderEntity(
  val connectionId: Long,
  val path: String,
  val query: String,
  val subjectId: Long?,
  val manual: Boolean,
  val episodeOffset: Int,
  val attemptedAt: Long,
  @androidx.room.ColumnInfo(defaultValue = "NULL") val parentPath: String? = null,
  @androidx.room.ColumnInfo(defaultValue = "NULL") val partNumber: Int? = null,
)
