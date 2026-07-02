package com.spotipremium.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val imageUrl: String = "",
    val songCount: Int = 0
)

@Entity(
    tableName = "songs",
    foreignKeys = [ForeignKey(
        entity = Playlist::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("playlistId")]
)
data class Song(
    @PrimaryKey val id: String,
    val playlistId: String,
    val name: String,
    val artist: String,
    val localPath: String = "",
    val youtubeUrl: String = "",
    val downloaded: Boolean = false,
    val gain: Float = 1.0f
)
