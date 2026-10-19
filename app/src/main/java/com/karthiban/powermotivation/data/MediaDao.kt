package com.karthiban.powermotivation.data

import androidx.room.*
import com.karthiban.powermotivation.model.MediaItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_items ORDER BY sortOrder ASC, id ASC")
    fun observeAllMedia(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllMediaSync(): List<MediaItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedia(item: MediaItemEntity): Long

    @Update
    suspend fun updateMedia(items: List<MediaItemEntity>)

    @Delete
    suspend fun deleteMedia(item: MediaItemEntity)

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM media_items")
    suspend fun getMaxSortOrder(): Int
}