package com.karthiban.powermotivation.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MediaType {
    PHOTO,
    VIDEO
}

enum class PlaylistTarget {
    BOTH,
    HOME_ONLY,
    LOCK_ONLY
}

@Entity(tableName = "media_items")
data class MediaItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uriString: String,
    val displayName: String,
    val mimeType: String,
    val mediaType: MediaType,
    val sortOrder: Int,
    val playlistTarget: PlaylistTarget = PlaylistTarget.BOTH,
    val dateAdded: Long = System.currentTimeMillis()
)