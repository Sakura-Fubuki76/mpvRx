package app.gyrolet.mpvrx.database.dao

import androidx.room.*
import app.gyrolet.mpvrx.database.entities.AnimeFolderEntity
import app.gyrolet.mpvrx.database.entities.AnimeSubjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class AnimeDao {
  @Query("DELETE FROM anime_folders WHERE connectionId = :id")
  abstract suspend fun clearConnection(id: Long)
  @Query("SELECT * FROM anime_folders WHERE connectionId = :id")
  abstract fun observeFolders(id: Long): Flow<List<AnimeFolderEntity>>
  @Query("SELECT * FROM anime_folders WHERE connectionId = :id AND manual = 1")
  abstract suspend fun getManualFolders(id: Long): List<AnimeFolderEntity>
  @Query("SELECT * FROM anime_subjects")
  abstract fun observeSubjects(): Flow<List<AnimeSubjectEntity>>
  @Query("SELECT * FROM anime_folders WHERE connectionId = :id AND path = :path")
  abstract suspend fun getFolder(id: Long, path: String): AnimeFolderEntity?
  @Query("SELECT * FROM anime_subjects WHERE id = :id")
  abstract suspend fun getSubject(id: Long): AnimeSubjectEntity?
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun putSubject(subject: AnimeSubjectEntity)
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  abstract suspend fun putFolder(folder: AnimeFolderEntity)
  @Query("DELETE FROM anime_folders WHERE connectionId = :id AND path = :path AND query LIKE '#group:%'")
  abstract suspend fun removeAssignment(id: Long, path: String)
  @Transaction
  open suspend fun bindAutomatically(folder: AnimeFolderEntity) {
    if (getFolder(folder.connectionId, folder.path)?.manual != true) putFolder(folder)
  }
}
