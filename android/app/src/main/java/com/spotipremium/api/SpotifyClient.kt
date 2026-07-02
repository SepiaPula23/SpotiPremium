package com.spotipremium.api

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.spotipremium.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SpotifyClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    var serverUrl: String = ""
    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
    private val tokenClient = SpotifyTokenClient()

    suspend fun searchPlaylists(query: String): List<PlaylistResult> = withContext(Dispatchers.IO) {
        if (serverUrl.isNotBlank()) {
            try {
                val url = "$serverUrl/api/search/spotify?q=${URLEncoder.encode(query, "UTF-8")}"
                val resp = client.newCall(Request.Builder().url(url).build()).execute()
                try {
                    if (resp.code == 200) {
                        val json = resp.body?.string() ?: return@withContext emptyList()
                        val root = JsonParser.parseString(json).asJsonObject
                        val arr = root.getAsJsonArray("results") ?: return@withContext emptyList()
                        val list = mutableListOf<PlaylistResult>()
                        for (i in 0 until arr.size()) {
                            val item = arr.get(i).asJsonObject
                            list.add(PlaylistResult(
                                id = item.get("id").asString,
                                name = item.get("name").asString,
                                description = item.get("description")?.asString ?: "",
                                imageUrl = item.get("imageUrl")?.asString ?: "",
                                songCount = item.get("songCount")?.asInt ?: 0
                            ))
                        }
                        return@withContext list
                    }
                } finally { resp.close() }
            } catch (_: Exception) {}
        }
        emptyList()
    }

    suspend fun getPlaylist(input: String): PlaylistDetail? = withContext(Dispatchers.IO) {
        val id = extractPlaylistId(input) ?: return@withContext null

        // If PC server configured, use it for full playlist import (ALL tracks)
        if (serverUrl.isNotBlank()) {
            try {
                val url = "$serverUrl/api/import/spotify?url=https://open.spotify.com/playlist/$id"
                val resp = client.newCall(Request.Builder().url(url).build()).execute()
                try {
                    if (resp.code == 200) {
                        val json = resp.body?.string() ?: return@withContext null
                        val root = JsonParser.parseString(json).asJsonObject
                        val name = root.get("name")?.asString ?: "Playlist"
                        val tracks = root.getAsJsonArray("tracks") ?: return@withContext null
                        val songs = mutableListOf<Song>()
                        for (i in 0 until tracks.size()) {
                            val t = tracks.get(i).asJsonObject
                            val title = t.get("name")?.asString ?: continue
                            val artist = t.get("artist")?.asString ?: "Unknown"
                            val trackId = "${id}_$i"
                            songs.add(Song(id = trackId, playlistId = id,
                                name = title, artist = artist, downloaded = false))
                        }
                        if (songs.isNotEmpty()) {
                            return@withContext PlaylistDetail(id = id, name = name,
                                description = "", imageUrl = "", songs = songs)
                        }
                    }
                } finally { resp.close() }
            } catch (_: Exception) {}
        }

        // Standalone: fetch embed page
        val html = try {
            val resp = client.newCall(Request.Builder()
                .url("https://open.spotify.com/embed/playlist/$id")
                .addHeader("User-Agent", ua)
                .addHeader("Accept", "text/html")
                .build()).execute()
            try { if (resp.code == 200) resp.body?.string() else null }
            finally { resp.close() }
        } catch (_: Exception) { null } ?: return@withContext null

        val detail = parseEmbedPage(html, id) ?: return@withContext null

        // Try TOTP-based token first (much more likely to work for pagination)
        val apiToken = tokenClient.getToken()?.accessToken
        if (!apiToken.isNullOrBlank() && detail.songs.isNotEmpty()) {
            paginateTracks(id, detail.name, apiToken, detail.songs)
        } else {
            // Fallback: embed page token
            val accessToken = extractAccessToken(html)
            if (accessToken.isNotBlank() && detail.songs.isNotEmpty()) {
                paginateTracks(id, detail.name, accessToken, detail.songs)
            }
        }

        detail
    }

    /** Parse the embed page HTML and extract playlist info + first batch of tracks. */
    private fun parseEmbedPage(html: String, playlistId: String): PlaylistDetail? {
        try {
            val start = html.indexOf("__NEXT_DATA__")
            if (start < 0) return null
            val jsonStart = html.indexOf(">", start + 14) + 1
            val jsonEnd = html.indexOf("</script>", jsonStart)
            if (jsonEnd < 0) return null
            val json = html.substring(jsonStart, jsonEnd)

            val root = JsonParser.parseString(json).asJsonObject
            val entity = root
                .getAsJsonObject("props")?.getAsJsonObject("pageProps")
                ?.getAsJsonObject("state")?.getAsJsonObject("data")
                ?.getAsJsonObject("entity") ?: return null

            val name = entity.get("title")?.asString
                ?: entity.get("name")?.asString ?: "Unknown"

            val coverUrl = entity.getAsJsonObject("coverArt")
                ?.getAsJsonArray("sources")?.last()
                ?.asJsonObject?.get("url")?.asString ?: ""

            val songs = mutableListOf<Song>()
            val trackList = entity.getAsJsonArray("trackList")
            if (trackList != null) {
                for (i in 0 until trackList.size()) {
                    try {
                        val t = trackList.get(i).asJsonObject
                        val title = t.get("title")?.asString ?: continue
                        val artist = t.get("subtitle")?.asString ?: "Unknown"
                        val uri = t.get("uri")?.asString ?: ""
                        val trackId = uri.substringAfterLast(":")
                            .ifEmpty { "${playlistId}_$i" }
                        if (songs.none { it.id == trackId }) {
                            songs.add(Song(id = trackId, playlistId = playlistId,
                                name = title, artist = artist, downloaded = false))
                        }
                    } catch (_: Exception) {}
                }
            }

            return PlaylistDetail(id = playlistId, name = name,
                description = "", imageUrl = coverUrl, songs = songs)
        } catch (_: Exception) { return null }
    }

    /** Extract an access token from the embed page or from the public token endpoint. */
    private fun extractAccessToken(html: String): String {
        // Try embed page first
        val m = Regex(""""accessToken"\s*:\s*"([^"]+)"""").find(html)
        if (m != null) return m.groupValues[1]
        // Fallback: public anonymous token endpoint
        return try {
            val resp = client.newCall(Request.Builder()
                .url("https://open.spotify.com/get_access_token")
                .addHeader("User-Agent", ua)
                .addHeader("Referer", "https://open.spotify.com/")
                .build()).execute()
            try {
                if (resp.code == 200) {
                    val json = resp.body?.string() ?: ""
                    val t = Regex(""""accessToken"\s*:\s*"([^"]+)"""").find(json)
                    t?.groupValues?.get(1) ?: ""
                } else ""
            } finally { resp.close() }
        } catch (_: Exception) { "" }
    }

    /** Paginate through the Spotify Web API to find tracks beyond the first page. */
    private fun paginateTracks(playlistId: String, playlistName: String,
                               accessToken: String, songs: MutableList<Song>) {
        try {
            var offset = 0
            val limit = 50
            val existingIds = songs.map { it.id }.toHashSet()
            var total = Int.MAX_VALUE

            while (offset < total) {
                val url = "https://api.spotify.com/v1/playlists/$playlistId/tracks?limit=$limit&offset=$offset"
                val resp = client.newCall(Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("User-Agent", ua)
                    .addHeader("Accept", "application/json")
                    .addHeader("Origin", "https://open.spotify.com")
                    .build()).execute()
                try {
                    if (resp.code != 200) break
                    val body = resp.body?.string() ?: break
                    val root = JsonParser.parseString(body).asJsonObject
                    val items = root.getAsJsonArray("items") ?: break
                    if (items.size() == 0) break

                    for (i in 0 until items.size()) {
                        val item = items.get(i).asJsonObject
                        val track = item.getAsJsonObject("track") ?: continue
                        val tid = track.get("id")?.asString ?: continue
                        if (tid in existingIds) continue
                        val title = track.get("name")?.asString ?: continue
                        val artists = track.getAsJsonArray("artists")?.let { arr ->
                            (0 until arr.size()).joinToString(", ") {
                                arr.get(it).asJsonObject.get("name")?.asString ?: ""
                            }
                        } ?: "Unknown"
                        songs.add(Song(id = tid, playlistId = playlistId,
                            name = title, artist = artists, downloaded = false))
                        existingIds.add(tid)
                    }

                    total = root.get("total")?.asInt ?: break
                    offset += limit
                } finally { resp.close() }
            }
        } catch (_: Exception) { /* pagination failed, keep existing songs */ }
    }

    private fun extractPlaylistId(input: String): String? {
        val patterns = listOf(
            Regex("open\\.spotify\\.com/playlist/([a-zA-Z0-9]+)"),
            Regex("spotify:playlist:([a-zA-Z0-9]+)"),
            Regex("^([a-zA-Z0-9]{22})$")
        )
        for (p in patterns) {
            val m = p.find(input.trim())
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    data class PlaylistResult(
        val id: String, val name: String, val description: String,
        val imageUrl: String, val songCount: Int
    )
    data class PlaylistDetail(
        val id: String, val name: String, val description: String,
        val imageUrl: String, val songs: MutableList<Song>
    )
}
