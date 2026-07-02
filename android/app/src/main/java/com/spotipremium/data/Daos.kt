package com.spotipremium.data

import androidx.room.*

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name")
    suspend fun getAll(): List<Playlist>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: Playlist)

    @Delete
    suspend fun delete(playlist: Playlist)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: String): Playlist?

    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun count(): Int
}

@Dao
interface SongDao {
    @Query("SELECT * FROM songs WHERE playlistId = :playlistId ORDER BY name")
    suspend fun getByPlaylist(playlistId: String): List<Song>

    @Query("SELECT * FROM songs WHERE downloaded = 1 ORDER BY artist, name")
    suspend fun getAllDownloaded(): List<Song>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<Song>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(song: Song)

    @Query("UPDATE songs SET downloaded = 1, localPath = :path WHERE id = :id")
    suspend fun markDownloaded(id: String, path: String)

    @Query("UPDATE songs SET localPath = '', downloaded = 0 WHERE id = :id")
    suspend fun markNotDownloaded(id: String)

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: String): Song?

    @Query("DELETE FROM songs WHERE playlistId = :playlistId")
    suspend fun deleteByPlaylist(playlistId: String)

    @Query("SELECT COUNT(*) FROM songs WHERE downloaded = 1")
    suspend fun countDownloaded(): Int

    @Query("UPDATE songs SET youtubeUrl = :url WHERE id = :id")
    suspend fun updateYoutubeUrl(id: String, url: String)

    @Query("UPDATE songs SET gain = :gain WHERE id = :id")
    suspend fun updateGain(id: String, gain: Float)

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getMultiple(ids: List<String>): List<Song>
}
