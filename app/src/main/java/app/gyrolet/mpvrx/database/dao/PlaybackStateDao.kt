/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity

@Dao
interface PlaybackStateDao {
  @Upsert
  suspend fun upsert(playbackStateEntity: PlaybackStateEntity)

  @Query("SELECT * FROM PlaybackStateEntity WHERE mediaTitle = :mediaTitle LIMIT 1")
  suspend fun getVideoDataByTitle(mediaTitle: String): PlaybackStateEntity?

  @Query("SELECT * FROM PlaybackStateEntity WHERE mediaTitle = :mediaTitle LIMIT 1")
  fun observeVideoData(mediaTitle: String): kotlinx.coroutines.flow.Flow<PlaybackStateEntity?>

  @Query("SELECT * FROM PlaybackStateEntity WHERE mediaTitle IN (:identifiers) ORDER BY CASE WHEN mediaTitle = :preferred THEN 0 ELSE 1 END LIMIT 1")
  fun observeVideoIdentifiers(identifiers: List<String>, preferred: String): kotlinx.coroutines.flow.Flow<PlaybackStateEntity?>

  @Query("SELECT * FROM PlaybackStateEntity WHERE mediaTitle IN (:identifiers)")
  suspend fun getVideoStates(identifiers: List<String>): List<PlaybackStateEntity>

  @Query("SELECT * FROM PlaybackStateEntity WHERE mediaTitle IN (:identifiers)")
  fun observeVideoStates(identifiers: List<String>): kotlinx.coroutines.flow.Flow<List<PlaybackStateEntity>>

  @Query("DELETE FROM PlaybackStateEntity")
  suspend fun clearAllPlaybackStates()

  @Query("DELETE FROM PlaybackStateEntity WHERE mediaTitle = :mediaTitle")
  suspend fun deleteByTitle(mediaTitle: String)

  @Query(
    """
    UPDATE PlaybackStateEntity 
    SET mediaTitle = :newTitle 
    WHERE mediaTitle = :oldTitle
  """,
  )
  suspend fun updateMediaTitle(
    oldTitle: String,
    newTitle: String,
  )

  @Query("SELECT * FROM PlaybackStateEntity")
  suspend fun getAllPlaybackStates(): List<PlaybackStateEntity>

  @Upsert
  suspend fun upsertAll(playbackStates: List<PlaybackStateEntity>)
}
