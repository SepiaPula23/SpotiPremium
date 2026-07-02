package com.spotipremium.api

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class YouTubeClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val serverClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    var serverUrl: String = ""

    private val invidiousInstances = listOf(
        "https://inv.nadeko.net", "https://yewtu.be",
        "https://inv.tux.pizza", "https://inv.bparker.xyz",
    )

    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks", "https://pipedapi-libre.kavin.rocks",
        "https://pipedapi.syncpundit.io", "https://pipedapi.tokhmi.xyz",
        "https://api-piped.mha.fi",
    )

    data class SearchResult(val title: String, val url: String, val uploaderName: String, val lengthSeconds: Int = 0)
    data class AudioStream(val url: String, val mimeType: String, val quality: String)

    suspend fun searchSong(artist: String, name: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val query = "$artist $name"

        if (serverUrl.isNotBlank()) {
            val r = searchViaServer(query)
            if (r != null) {
                val scored = scoreAndSort(r, artist, name)
                if (scored.isNotEmpty()) return@withContext scored
            }
        }

        val piped = withTimeoutOrNull(8000) { searchViaPiped(query, artist, name) }
        if (piped != null) return@withContext piped

        val invidious = withTimeoutOrNull(8000) { searchViaInvidious(query, artist, name) }
        if (invidious != null) return@withContext invidious

        val innerTube = withTimeoutOrNull(10000) { innerTubeSearch(query, artist, name) }
        if (innerTube != null) return@withContext innerTube

        emptyList()
    }

    suspend fun downloadViaServer(videoId: String, destPath: String): Boolean = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank()) return@withContext false
        try {
            val url = "$serverUrl/api/youtube/download?video_id=$videoId"
            val resp = serverClient.newCall(Request.Builder().url(url).build()).execute()
            if (resp.code != 200) return@withContext false
            resp.body?.byteStream()?.use { input ->
                java.io.File(destPath).outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext false
            true
        } catch (_: Exception) { false }
    }

    suspend fun getAudioStream(videoId: String): AudioStream? = withContext(Dispatchers.IO) {
        if (serverUrl.isNotBlank()) {
            val s = streamViaServer(videoId)
            if (s != null) return@withContext s
        }

        val piped = withTimeoutOrNull(4000) { streamViaPiped(videoId) }
        if (piped != null) return@withContext piped

        val invidious = withTimeoutOrNull(4000) { streamViaInvidious(videoId) }
        if (invidious != null) return@withContext invidious

        val innerTube = withTimeoutOrNull(5000) { getInnerTubeStream(videoId) }
        if (innerTube != null) return@withContext innerTube

        null
    }

    private fun searchViaServer(query: String): List<SearchResult>? {
        return try {
            val url = "$serverUrl/api/youtube/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val resp = serverClient.newCall(Request.Builder().url(url).build()).execute()
            try {
                if (resp.code != 200) return null
                val json = resp.body?.string() ?: return null
                val root = JsonParser.parseString(json).asJsonObject
                val items = root.getAsJsonArray("results") ?: return null
                val results = mutableListOf<SearchResult>()
                for (i in 0 until items.size()) {
                    val obj = items.get(i).asJsonObject
                    val title = obj.get("title")?.asString ?: ""
                    val vid = obj.get("id")?.asString ?: ""
                    val uploader = obj.get("uploader")?.asString ?: ""
                    val duration = obj.get("duration")?.asInt ?: 0
                    if (vid.isNotBlank())
                        results.add(SearchResult(title, "/watch?v=$vid", uploader, duration))
                }
                if (results.isNotEmpty()) results else null
            } finally { resp.close() }
        } catch (_: Exception) { null }
    }

    private fun searchViaPiped(query: String, artist: String, name: String): List<SearchResult>? {
        for (instance in pipedInstances) {
            try {
                val body = gson.toJson(mapOf("q" to query, "filter" to "videos"))
                val req = Request.Builder()
                    .url("$instance/search").post(body.toRequestBody("application/json".toMediaType()))
                    .addHeader("User-Agent", "Potify/1.0").build()
                val resp = client.newCall(req).execute()
                try {
                    if (resp.code == 200) {
                        val json = resp.body?.string()
                        if (json != null) {
                            val r = parseSearchResults(json, artist, name)
                            if (r.isNotEmpty()) return r
                        }
                    }
                } finally { resp.close() }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun searchViaInvidious(query: String, artist: String, name: String): List<SearchResult>? {
        for (instance in invidiousInstances) {
            try {
                val url = "$instance/api/v1/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&type=video"
                val resp = client.newCall(Request.Builder().url(url).build()).execute()
                try {
                    if (resp.code == 200) {
                        val json = resp.body?.string() ?: continue
                        val r = parseInvidiousSearch(json, artist, name)
                        if (r.isNotEmpty()) return r
                    }
                } finally { resp.close() }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun streamViaServer(videoId: String): AudioStream? {
        return try {
            val url = "$serverUrl/api/youtube/stream?video_id=$videoId"
            val resp = serverClient.newCall(Request.Builder().url(url).build()).execute()
            try {
                if (resp.code != 200) return null
                val json = resp.body?.string() ?: return null
                val root = JsonParser.parseString(json).asJsonObject
                val streamUrl = root.get("url")?.asString
                if (!streamUrl.isNullOrBlank())
                    AudioStream(url = streamUrl, mimeType = "m4a", quality = "")
                else null
            } finally { resp.close() }
        } catch (_: Exception) { null }
    }

    private fun streamViaPiped(videoId: String): AudioStream? {
        for (instance in pipedInstances) {
            try {
                val req = Request.Builder()
                    .url("$instance/streams/$videoId")
                    .addHeader("User-Agent", "Potify/1.0").build()
                val resp = streamClient.newCall(req).execute()
                try {
                    if (resp.code == 200) {
                        val json = resp.body?.string()
                        if (json != null) {
                            val s = parsePipedStreams(json)
                            if (s != null) return s
                        }
                    }
                } finally { resp.close() }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun streamViaInvidious(videoId: String): AudioStream? {
        for (instance in invidiousInstances) {
            try {
                val url = "$instance/api/v1/videos/$videoId"
                val resp = streamClient.newCall(Request.Builder().url(url).build()).execute()
                try {
                    if (resp.code == 200) {
                        val json = resp.body?.string() ?: continue
                        val s = parseInvidiousStreams(json)
                        if (s != null) return s
                    }
                } finally { resp.close() }
            } catch (_: Exception) {}
        }
        return null
    }

    // --- InnerTube ---
    private val innertubeKey = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private val innertubeUrl = "https://www.youtube.com/youtubei/v1"

    private data class ITClient(val name: String, val version: String, val clientId: Int, val userAgent: String)

    private val innertubeClients = listOf(
        ITClient("ANDROID", "19.09.37", 3, "com.google.android.youtube/19.09.37 (Linux; U; Android 14)"),
        ITClient("ANDROID_MUSIC", "6.27.51", 21, "com.google.android.apps.youtube.music/6.27.51 (Linux; U; Android 14)"),
        ITClient("IOS", "19.09.37", 5, "com.google.ios.youtube/19.09.37 (iPhone16,2; U; CPU iOS 17_4)"),
    )

    private fun buildITRequest(cfg: ITClient, extra: Map<String, Any>): Request {
        val body = mapOf("context" to mapOf("client" to mapOf(
            "clientName" to cfg.name, "clientVersion" to cfg.version, "hl" to "en", "gl" to "US"
        ))) + extra
        return Request.Builder()
            .url("$innertubeUrl/${extra.keys.first()}?key=$innertubeKey")
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .addHeader("User-Agent", cfg.userAgent)
            .addHeader("X-YouTube-Client-Name", cfg.clientId.toString())
            .addHeader("X-YouTube-Client-Version", cfg.version)
            .build()
    }

    private fun innerTubeSearch(query: String, artist: String, name: String): List<SearchResult>? {
        for (cfg in innertubeClients) {
            try {
                val req = buildITRequest(cfg, mapOf("search" to mapOf("query" to query)))
                val resp = streamClient.newCall(req).execute()
                try {
                    if (resp.code == 200) {
                        val json = resp.body?.string() ?: continue
                        val r = parseInnerTubeSearch(json, artist, name)
                        if (r.isNotEmpty()) return r
                    }
                } finally { resp.close() }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun getInnerTubeStream(videoId: String): AudioStream? {
        for (cfg in innertubeClients) {
            try {
                val req = buildITRequest(cfg, mapOf("player" to mapOf("videoId" to videoId)))
                val resp = streamClient.newCall(req).execute()
                try {
                    if (resp.code == 200) {
                        val json = resp.body?.string() ?: continue
                        val s = parseInnerTubeStreams(json)
                        if (s != null) return s
                    }
                } finally { resp.close() }
            } catch (_: Exception) {}
        }
        return null
    }

    // --- Parsers ---
    private fun parseSearchResults(json: String, artist: String, name: String): List<SearchResult> {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val items = root.getAsJsonArray("items") ?: return emptyList()
            val results = mutableListOf<SearchResult>()
            for (i in 0 until items.size()) {
                val obj = items.get(i).asJsonObject
                val title = obj.get("title")?.asString ?: ""
                val url = obj.get("url")?.asString ?: ""
                val uploader = obj.get("uploaderName")?.asString ?: ""
                val duration = obj.get("duration")?.asInt ?: 0
                if (url.isNotBlank()) results.add(SearchResult(title, url, uploader, duration))
            }
            scoreAndSort(results, artist, name)
        } catch (_: Exception) { emptyList() }
    }

    private fun parseInnerTubeSearch(json: String, artist: String, name: String): List<SearchResult> {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val results = mutableListOf<SearchResult>()
            val videos = mutableListOf<com.google.gson.JsonObject>()
            try {
                val items = root.getAsJsonObject("contents")
                    ?.getAsJsonObject("twoColumnSearchResultsRenderer")
                    ?.getAsJsonObject("primaryContents")
                    ?.getAsJsonObject("sectionListRenderer")
                    ?.getAsJsonArray("contents")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("itemSectionRenderer")
                    ?.getAsJsonArray("contents")
                if (items != null) {
                    for (i in 0 until items.size()) {
                        val v = items.get(i).asJsonObject.getAsJsonObject("videoRenderer")
                        if (v != null) videos.add(v)
                    }
                }
            } catch (_: Exception) {}

            for (video in videos) {
                try {
                    val title = video.getAsJsonObject("title")?.getAsJsonArray("runs")
                        ?.get(0)?.asJsonObject?.get("text")?.asString ?: continue
                    val videoId = video.get("videoId")?.asString ?: continue
                    val uploader = video.getAsJsonObject("ownerText")?.getAsJsonArray("runs")
                        ?.get(0)?.asJsonObject?.get("text")?.asString ?: ""
                    val lengthText = video.getAsJsonObject("lengthText")?.get("simpleText")?.asString
                        ?: video.getAsJsonObject("lengthText")?.getAsJsonArray("runs")
                            ?.get(0)?.asJsonObject?.get("text")?.asString ?: ""
                    val lengthSec = if (lengthText.contains(":")) parseLengthSeconds(lengthText) else 0
                    results.add(SearchResult(title, "/watch?v=$videoId", uploader, lengthSec))
                } catch (_: Exception) {}
            }
            if (results.isEmpty()) return emptyList()
            scoreAndSort(results, artist, name)
        } catch (_: Exception) { emptyList() }
    }

    private fun parseInnerTubeStreams(json: String): AudioStream? {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val adaptive = root.getAsJsonObject("streamingData")?.getAsJsonArray("adaptiveFormats") ?: return null
            val streams = mutableListOf<AudioStream>()
            for (i in 0 until adaptive.size()) {
                val f = adaptive.get(i).asJsonObject
                val mime = f.get("mimeType")?.asString ?: ""
                if (!mime.contains("audio")) continue
                val url = f.get("url")?.asString ?: continue
                val quality = (f.get("audioQuality")?.asString ?: "").replace("AUDIO_QUALITY_", "").lowercase()
                streams.add(AudioStream(url, mime, quality))
            }
            streams.sortedByDescending { s ->
                val typeScore = if (s.mimeType.contains("m4a") || s.mimeType.contains("mp4")) 30
                    else if (s.mimeType.contains("opus")) 20 else 10
                val qualityScore = if (s.quality.contains("medium")) 5 else if (s.quality.contains("low")) 1 else 10
                typeScore * 10 + qualityScore
            }.firstOrNull()
        } catch (_: Exception) { null }
    }

    private fun parsePipedStreams(json: String): AudioStream? {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val list = root.getAsJsonArray("audioStreams")?.mapNotNull { obj ->
                val o = obj.asJsonObject
                val url = o.get("url")?.asString ?: return@mapNotNull null
                AudioStream(url, o.get("mimeType")?.asString ?: "", o.get("quality")?.asString ?: "")
            }?.sortedByDescending { s ->
                val typeScore = if (s.mimeType.contains("m4a") || s.mimeType.contains("mp4")) 10
                    else if (s.mimeType.contains("opus")) 5 else 0
                typeScore * 100 + (s.quality.replace(" kbps", "").toIntOrNull() ?: 0)
            } ?: return null
            list.firstOrNull()
        } catch (_: Exception) { null }
    }

    private fun parseInvidiousSearch(json: String, artist: String, name: String): List<SearchResult> {
        return try {
            val arr = JsonParser.parseString(json).asJsonArray
            val results = mutableListOf<SearchResult>()
            for (i in 0 until arr.size()) {
                val obj = arr.get(i).asJsonObject
                if (obj.get("type")?.asString != "video") continue
                val title = obj.get("title")?.asString ?: continue
                val videoId = obj.get("videoId")?.asString ?: continue
                results.add(SearchResult(title, "/watch?v=$videoId", obj.get("author")?.asString ?: "", obj.get("lengthSeconds")?.asInt ?: 0))
            }
            scoreAndSort(results, artist, name)
        } catch (_: Exception) { emptyList() }
    }

    private fun parseInvidiousStreams(json: String): AudioStream? {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            root.getAsJsonArray("adaptiveFormats")?.mapNotNull { f ->
                val o = f.asJsonObject
                val type = o.get("type")?.asString ?: return@mapNotNull null
                if (!type.contains("audio")) return@mapNotNull null
                AudioStream(o.get("url")?.asString ?: return@mapNotNull null,
                    type.split(";").firstOrNull() ?: type,
                    o.get("qualityLabel")?.asString ?: o.get("audioQuality")?.asString ?: "")
            }?.sortedByDescending { s ->
                val typeScore = if (s.mimeType.contains("m4a") || s.mimeType.contains("mp4")) 30
                    else if (s.mimeType.contains("opus")) 20 else 10
                val qualityScore = if (s.quality.contains("medium")) 5 else if (s.quality.contains("low")) 1 else 10
                typeScore * 10 + qualityScore
            }?.firstOrNull()
        } catch (_: Exception) { null }
    }

    private fun scoreResult(result: SearchResult, artist: String, name: String): Int {
        val t = result.title.lowercase()
        val ul = result.uploaderName.lowercase()
        val a = artist.lowercase().trim()
        val s = name.lowercase().trim()
        val aw = a.split(Regex("\\s+")).filter { it.length > 2 }
        val sw = s.split(Regex("\\s+")).filter { it.length > 2 }

        // All song name words MUST be in the title or score is 0
        if (sw.isNotEmpty() && sw.any { w -> !t.contains(w) }) return 0

        // At least one artist word MUST be in the title (if artist has significant words)
        if (aw.isNotEmpty() && aw.none { w -> t.contains(w) }) return 0

        var score = 0
        // Full song name in title = strong match
        if (t.contains(s)) score += 200
        // Each matching artist word
        score += aw.count { w -> t.contains(w) } * 40
        // Uploader matches artist
        if (aw.isNotEmpty() && aw.any { w -> ul.contains(w) }) score += 80
        else score -= 50

        // Channel type bonuses
        if (ul.contains("topic")) score += 100
        if (ul.contains("vevo")) score += 50
        if (t.contains("official audio")) score += 60
        if (t.contains("official video")) score += 40
        if (t.contains("audio")) score -= 20
        if (t.contains("video")) score -= 20

        // Strong penalties for non-original content
        if (t.contains("live") || t.contains("en vivo") || t.contains("concert")) score -= 1000
        if (t.contains("cover") || t.contains("version")) score -= 800
        if (t.contains("remix") || t.contains("mashup") || t.contains("medley")) score -= 800
        if (t.contains("instrumental") || t.contains("karaoke") || t.contains("tutorial")) score -= 1000
        if (t.contains("reaction") || t.contains("interview") || t.contains("podcast")) score -= 1000
        if (t.contains("sped up") || t.contains("slowed") || t.contains("loop")) score -= 600
        if (t.contains("extended") || t.contains("edit")) score -= 400
        if (t.contains("lyrics") || t.contains("letra")) score -= 300
        if (t.contains("visualizer") || t.contains("performance")) score -= 400
        if (t.contains("making of") || t.contains("behind the")) score -= 1000

        // Duration check
        if (result.lengthSeconds in 1..119) score -= 500

        return score
    }

    private fun scoreAndSort(results: List<SearchResult>, artist: String, name: String): List<SearchResult> {
        if (results.isEmpty()) return results
        val songName = name.lowercase().trim()
        val artistName = artist.lowercase().trim()
        return results.map { it to scoreResult(it, artist, name) }
            .filter { (_, score) -> score >= 200 }
            .sortedByDescending { it.second }.map { it.first }
    }

    private fun parseLengthSeconds(length: String): Int {
        val parts = length.split(":")
        return when (parts.size) {
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            else -> parts[0].toIntOrNull() ?: 0
        }
    }
}
