package com.spotipremium.service

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.spotipremium.R
import com.spotipremium.api.RemoteLogger
import com.spotipremium.api.YouTubeClient
import com.spotipremium.data.AppDatabase
import com.spotipremium.data.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db by lazy { AppDatabase.get(this) }
    private val ytClient = YouTubeClient()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val downloadMutex = Mutex()
    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress

    private var pendingTasks = 0
    @Volatile private var isCancelled = false

    private fun refreshServerUrl() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        val ip = prefs.getString("serverIp", "") ?: ""
        val url = if (ip.isBlank()) "" else "http://$ip:8000"
        ytClient.serverUrl = url
        RemoteLogger.refreshUrl(url)
    }

    @Synchronized private fun inc() { pendingTasks++ }
    @Synchronized private fun dec() {
        pendingTasks--
        if (pendingTasks <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        val ip = prefs.getString("serverIp", "") ?: ""
        val url = if (ip.isBlank()) "" else "http://$ip:8000"
        ytClient.serverUrl = url
        RemoteLogger.init(this, url)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshServerUrl()
        when (intent?.action) {
            ACTION_CANCEL_DOWNLOAD -> {
                isCancelled = true
                serviceScope.coroutineContext.cancelChildren()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DOWNLOAD -> {
                isCancelled = false
                startForeground(NOTIFICATION_ID, buildNotification("Preparando descarga...", 0, 1))
                val playlistId = intent.getStringExtra("playlistId") ?: return START_NOT_STICKY
                val playlistName = intent.getStringExtra("playlistName") ?: "Playlist"
                inc()
                serviceScope.launch {
                    downloadMutex.withLock { downloadPlaylist(playlistId, playlistName) }
                    dec()
                }
            }
            ACTION_DOWNLOAD_SONG -> {
                isCancelled = false
                startForeground(NOTIFICATION_ID, buildNotification("Preparando descarga...", 0, 1))
                val songId = intent.getStringExtra("songId") ?: return START_NOT_STICKY
                val playlistName = intent.getStringExtra("playlistName") ?: "Playlist"
                inc()
                serviceScope.launch {
                    downloadMutex.withLock { downloadSingleSong(songId, playlistName) }
                    dec()
                }
            }
            ACTION_REDOWNLOAD_SONG -> {
                isCancelled = false
                startForeground(NOTIFICATION_ID, buildNotification("Preparando descarga...", 0, 1))
                val songId = intent.getStringExtra("songId") ?: return START_NOT_STICKY
                val playlistName = intent.getStringExtra("playlistName") ?: "Playlist"
                inc()
                serviceScope.launch {
                    downloadMutex.withLock { redownloadSingleSong(songId, playlistName) }
                    dec()
                }
            }
            ACTION_DOWNLOAD_SELECTED -> {
                isCancelled = false
                startForeground(NOTIFICATION_ID, buildNotification("Preparando descarga...", 0, 1))
                val ids = intent.getStringArrayListExtra("songIds") ?: return START_NOT_STICKY
                val playlistName = intent.getStringExtra("playlistName") ?: "Playlist"
                inc()
                serviceScope.launch {
                    downloadMutex.withLock { downloadSelected(ids, playlistName) }
                    dec()
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun downloadPlaylist(playlistId: String, playlistName: String) {
        var completedCount = 0
        var failedCount = 0
        try {
            val songs = db.songDao().getByPlaylist(playlistId).filter { !it.downloaded }
            if (songs.isEmpty()) return

            DownloadProgress(total = songs.size, songName = songs.firstOrNull()?.name ?: "").also {
                _progress.value = it; _globalProgress.value = it
            }
            updateNotification("Preparando...", 0, songs.size)

            var index = 0
            for (song in songs) {
                if (isCancelled) {
                    sendDebug("downloadPlaylist: cancelled by user")
                    return
                }
                _progress.value.copy(current = index, songName = "${song.artist} - ${song.name}").also {
                    _progress.value = it; _globalProgress.value = it
                }
                updateNotification("Descargando ${index + 1}/${songs.size}", index, songs.size)
                index++

                try {
                    val result = downloadSong(song, playlistName)
                    if (result != null) {
                        db.songDao().markDownloaded(song.id, result)
                        completedCount++
                    } else {
                        failedCount++
                    }
                } catch (_: Exception) { failedCount++ }
            }
        } finally {
            _progress.value.copy(finished = true, current = completedCount, failed = failedCount).also {
                _progress.value = it; _globalProgress.value = it
            }
            sendBroadcast(Intent(ACTION_DOWNLOAD_FINISHED).setPackage(packageName))
        }
    }

    private suspend fun downloadSingleSong(songId: String, playlistName: String) {
        try {
            if (isCancelled) return
            val song = db.songDao().getById(songId) ?: return
            if (song.downloaded) return

            updateNotification("Descargando ${song.name}", 0, 1)
            try {
                val result = downloadSong(song, playlistName)
                if (result != null) {
                    db.songDao().markDownloaded(song.id, result)
                } else {
                    sendBroadcast(Intent("com.spotipremium.DEBUG").apply {
                        putExtra("msg", "downloadSong returned null for ${song.name}")
                        setPackage(packageName)
                    })
                }
            } catch (e: Exception) {
                sendBroadcast(Intent("com.spotipremium.DEBUG").apply {
                    putExtra("msg", "downloadSong exception: ${e.message}")
                    setPackage(packageName)
                })
            }
        } finally {
            sendBroadcast(Intent(ACTION_DOWNLOAD_FINISHED).setPackage(packageName))
        }
    }

    private suspend fun redownloadSingleSong(songId: String, playlistName: String) {
        try {
            if (isCancelled) return
            val song = db.songDao().getById(songId) ?: return
            if (song.localPath.isNotBlank()) {
                deleteLocalPath(song.localPath)
            }
            db.songDao().markNotDownloaded(song.id)

            updateNotification("Re-descargando ${song.name}", 0, 1)
            val refreshed = db.songDao().getById(songId) ?: return
            try {
                val result = downloadSong(refreshed, playlistName)
                if (result != null) {
                    db.songDao().markDownloaded(song.id, result)
                }
            } catch (_: Exception) {}
        } finally {
            sendBroadcast(Intent(ACTION_DOWNLOAD_FINISHED).setPackage(packageName))
        }
    }

    private suspend fun downloadSelected(ids: List<String>, playlistName: String) {
        var completed = 0
        var failed = 0
        val total = ids.size
        updateNotification("Preparando...", 0, total)
        for ((i, id) in ids.withIndex()) {
            if (isCancelled) return
            val song = db.songDao().getById(id) ?: continue
            deleteLocalPath(song.localPath)
            db.songDao().markNotDownloaded(song.id)
            updateNotification("Re-descargando ${i + 1}/$total - ${song.name}", i, total)
            try {
                val refreshed = db.songDao().getById(id) ?: continue
                val result = downloadSong(refreshed, playlistName)
                if (result != null) {
                    db.songDao().markDownloaded(id, result)
                    completed++
                } else { failed++ }
            } catch (_: Exception) { failed++ }
        }
        sendBroadcast(Intent(ACTION_DOWNLOAD_FINISHED).setPackage(packageName))
    }

    private suspend fun downloadSong(song: Song, playlistName: String): String? {
        sendDebug("downloadSong start: ${song.artist} - ${song.name}, downloaded=${song.downloaded}, localPath=${song.localPath}, serverUrl=${ytClient.serverUrl}")
        val videoId: String
        if (song.youtubeUrl.isNotBlank()) {
            val vid = song.youtubeUrl.substringAfter("v=").substringBefore("&").take(11)
            if (vid.length == 11) {
                videoId = vid
            } else {
                sendDebug("youtubeUrl present but invalid vid='$vid' from '${song.youtubeUrl}'")
                return null
            }
        } else {
            sendDebug("searching: ${song.artist} - ${song.name}, serverUrl=${ytClient.serverUrl}")
            val results = ytClient.searchSong(song.artist, song.name)
            sendDebug("searchSong returned ${results.size} results")
            val best = selectBestMatch(results, song.artist, song.name)
            if (best == null) {
                sendDebug("selectBestMatch returned null from ${results.size} results")
                return null
            }
            videoId = best.url.removePrefix("/watch?v=").takeIf { it.length == 11 }
                ?: best.url.substringAfterLast("v=").take(11)
            sendDebug("selected videoId=$videoId from '${best.title}' (${best.url})")
        }

        val safePlaylist = sanitize(playlistName)

        for (attempt in 1..2) {
            if (isCancelled) {
                sendDebug("downloadSong: cancelled during stream fetch")
                return null
            }
            val stream = ytClient.getAudioStream(videoId)
            if (stream == null) {
                sendDebug("getAudioStream returned null (attempt $attempt)")
                continue
            }
            sendDebug("getAudioStream OK: ${stream.url.take(80)}... mime=${stream.mimeType}")
            val ext = when {
                stream.mimeType.contains("ogg") -> "ogg"
                stream.mimeType.contains("webm") -> "webm"
                stream.mimeType.contains("mp4") || stream.mimeType.contains("m4a") -> "m4a"
                else -> "m4a"
            }
            val fileName = "${sanitize(song.artist)} - ${sanitize(song.name)}.$ext"

            // Download to temp file
            val tempFile = File(cacheDir, "dl_${song.id}.$ext")
            var downloadOk = false
            try {
                downloadOk = withTimeoutOrNull(120_000) {
                    tempFile.outputStream().use { output ->
                        downloadStream(stream, output)
                    }
                } ?: false
            } catch (e: Exception) {
                sendDebug("downloadStream exception: ${e.message} (attempt $attempt)")
            }
            if (!downloadOk) {
                sendDebug("downloadStream failed (attempt $attempt)")
                tempFile.delete()
                continue
            }

            // Auto-trim intro/outro: keep 1s margin, cut remaining intro/outro for M4A
            val trimmedFile = if (ext == "m4a" && tempFile.length() > 200000) {
                val tf = File(cacheDir, "dl_trimmed_${song.id}.$ext")
                val trimmed = trimIntroOutro(tempFile.absolutePath, tf.absolutePath)
                if (trimmed) {
                    tempFile.delete()
                    tf
                } else {
                    tempFile // keep original if trim fails
                }
            } else {
                tempFile
            }

            sendDebug("writing $fileName via MediaStore")
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                copyToMediaStore(song, trimmedFile, fileName, safePlaylist)
            } else {
                copyToDirectPath(song, trimmedFile, fileName, safePlaylist)
            }
            trimmedFile.delete()
            if (result != null) {
                sendDebug("download OK: $result")
                return result
            }
            sendDebug("save to final destination returned null (attempt $attempt)")
        }
        sendDebug("all attempts exhausted, returning null")
        return null
    }

    private fun sendDebug(msg: String) {
        RemoteLogger.log("Download", msg)
    }

    private fun downloadStream(stream: YouTubeClient.AudioStream, output: java.io.OutputStream): Boolean {
        val req = Request.Builder()
            .url(stream.url)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .addHeader("Accept", "*/*")
            .addHeader("Range", "bytes=0-")
            .build()
        val resp = httpClient.newCall(req).execute()
        try {
            if (resp.code !in 200..299) return false
            val body = resp.body ?: return false
            val expected = body.contentLength()
            var downloaded: Long = 0
            body.byteStream().use { input ->
                val buf = ByteArray(65536)
                while (true) {
                    val bytes = input.read(buf)
                    if (bytes == -1) break
                    output.write(buf, 0, bytes)
                    downloaded += bytes
                }
            }
            if (expected > 0 && downloaded < expected) {
                sendBroadcast(Intent("com.spotipremium.DEBUG").apply {
                    putExtra("msg", "downloadStream incomplete: $downloaded/$expected bytes")
                    setPackage(packageName)
                })
                return false
            }
            val ok = downloaded >= 50000
            if (!ok) {
                sendBroadcast(Intent("com.spotipremium.DEBUG").apply {
                    putExtra("msg", "downloadStream too small: $downloaded bytes")
                    setPackage(packageName)
                })
            }
            return ok
        } finally {
            resp.close()
        }
    }

    private fun copyToMediaStore(song: Song, source: File, fileName: String, playlistName: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.TITLE, song.name)
            put(MediaStore.Audio.Media.ARTIST, song.artist)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/$playlistName")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: run {
                contentResolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return uri.toString()
        } catch (e: Exception) {
            try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            return null
        }
    }

    private fun copyToDirectPath(song: Song, source: File, fileName: String, playlistName: String): String? {
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val dir = File(baseDir, playlistName)
        dir.mkdirs()
        val file = File(dir, fileName)
        if (file.exists()) return file.absolutePath
        try {
            source.copyTo(file, overwrite = true)
            return file.absolutePath
        } catch (e: Exception) {
            file.delete()
            return null
        }
    }

    /** Remove first 3s and last 3s from an M4A file. Returns true on success. */
    private fun trimIntroOutro(inputPath: String, outputPath: String): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            extractor = MediaExtractor()
            extractor!!.setDataSource(inputPath)

            var audioTrackIndex = -1
            for (i in 0 until extractor!!.trackCount) {
                val fmt = extractor!!.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) { audioTrackIndex = i; break }
            }
            if (audioTrackIndex < 0) { extractor!!.release(); return false }

            extractor!!.selectTrack(audioTrackIndex)
            val trackFormat = extractor!!.getTrackFormat(audioTrackIndex)
            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) trackFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val cutUs = 2_000_000L // 2 seconds (keep 1s margin of silence)
            if (durationUs <= cutUs * 2 + 1_000_000) { // song too short or unknown duration, skip
                extractor!!.release()
                return false
            }

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrack = muxer!!.addTrack(trackFormat)
            muxer!!.start()

            extractor!!.seekTo(cutUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val buf = ByteBuffer.allocate(1024 * 1024)
            val endUs = durationUs - cutUs
            val info = MediaCodec.BufferInfo()

            while (true) {
                val sampleTime = extractor!!.sampleTime
                if (sampleTime < 0 || sampleTime > endUs) break

                val size = extractor!!.readSampleData(buf, 0)
                if (size < 0) break

                buf.rewind()
                info.offset = 0
                info.size = size
                info.presentationTimeUs = sampleTime - cutUs
                info.flags = extractor!!.sampleFlags

                muxer!!.writeSampleData(muxerTrack, buf, info)
                extractor!!.advance()
            }

            muxer!!.stop()
            muxer!!.release()
            extractor!!.release()
            true
        } catch (e: Exception) {
            try { extractor?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            false
        }
    }

    private fun selectBestMatch(results: List<YouTubeClient.SearchResult>, artist: String, name: String): YouTubeClient.SearchResult? {
        if (results.isEmpty()) {
            sendDebug("selectBestMatch: empty results")
            return null
        }
        val artistLower = artist.lowercase().trim()
        val songLower = name.lowercase().trim()
        val aw = artistLower.split(Regex("\\s+")).filter { it.length > 2 }
        val sw = songLower.split(Regex("\\s+")).filter { it.length > 2 }
        if (artistLower.isBlank() && songLower.isBlank()) {
            return results.firstOrNull { it.lengthSeconds >= 120 }
        }

        fun hasBadKeywords(t: String) = t.contains("live") || t.contains("en vivo") ||
            t.contains("cover") || t.contains("remix") || t.contains("instrumental") ||
            t.contains("karaoke") || t.contains("tutorial") || t.contains("reaction") ||
            t.contains("sped up") || t.contains("slowed") || t.contains("mashup") ||
            t.contains("medley") || t.contains("loop") || t.contains("extended") ||
            t.contains("lyrics") || t.contains("letra") ||
            t.contains("visualizer") || t.contains("performance") || t.contains("session") ||
            t.contains("radio") || t.contains("podcast") || t.contains("interview") ||
            t.contains("making of") || t.contains("behind the") || t.contains("story") ||
            t.contains("album") || t.contains("full album") || t.contains("full ep") ||
            t.contains("bootleg") || t.contains("dj mix") || t.contains("megamix") ||
            t.contains("tribute") || t.contains("parody")

        val clean = results.filter { r -> !hasBadKeywords(r.title.lowercase()) }
        if (clean.isEmpty()) {
            sendDebug("selectBestMatch: all results filtered by hasBadKeywords")
            return null
        }

        // Score every clean result and pick the best
        val scored = clean.mapNotNull { r ->
            val t = r.title.lowercase()
            val ul = r.uploaderName.lowercase()

            // REQUIRE: all song words in title (if song has significant words)
            if (sw.isNotEmpty() && sw.any { w -> !t.contains(w) }) return@mapNotNull null
            // REQUIRE: at least one artist word in title (if artist has significant words)
            if (aw.isNotEmpty() && aw.none { w -> t.contains(w) }) return@mapNotNull null
            // REQUIRE: minimum duration
            if (r.lengthSeconds in 1..119) return@mapNotNull null

            var score = 0
            // Full song name in title = strong signal
            if (t.contains(songLower)) score += 200
            // Artist word count bonus
            score += aw.count { w -> t.contains(w) } * 40
            // Uploader matches artist
            if (aw.isNotEmpty() && aw.any { w -> ul.contains(w) }) score += 80
            // Duration range
            if (r.lengthSeconds in 120..600) score += 50
            else if (r.lengthSeconds > 600) score += 20
            // Topic/Vevo uploader bonus
            if (ul.contains("topic")) score += 100
            if (ul.contains("vevo")) score += 50
            // Penalties for non-original content
            if (t.contains("live") || t.contains("en vivo")) score -= 1000
            if (t.contains("cover") || t.contains("version")) score -= 800
            if (t.contains("remix") || t.contains("mashup")) score -= 800
            if (t.contains("lyrics") || t.contains("letra")) score -= 300

            r to score
        }.filter { (_, s) -> s >= 250 }
            .sortedByDescending { it.second }

        val best = scored.firstOrNull()?.first
        if (best == null) {
            sendDebug("selectBestMatch: no result scored >= 250, top scores: ${scored.take(5).joinToString { "${it.first.title}=${it.second}" }}")
        } else {
            sendDebug("selectBestMatch: selected '${best.title}' (score=${scored.first().second})")
        }
        return best
    }

    private fun buildNotification(text: String, current: Int, total: Int): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Potify")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setProgress(total, current, false)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelIntent)
            .build()
    }

    private fun updateNotification(text: String, current: Int, total: Int) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text, current, total))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Descargas", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun deleteLocalPath(path: String) {
        if (path.isBlank()) return
        if (path.startsWith("content://")) {
            try { contentResolver.delete(Uri.parse(path), null, null) } catch (_: Exception) {}
        } else {
            try { File(path).delete() } catch (_: Exception) {}
        }
    }

    private fun sanitize(name: String): String {
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .trim()
            .take(200)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    data class DownloadProgress(
        val current: Int = 0,
        val total: Int = 0,
        val songName: String = "",
        val finished: Boolean = false,
        val failed: Int = 0
    )

    companion object {
        const val ACTION_CANCEL_DOWNLOAD = "com.spotipremium.CANCEL_DOWNLOAD"
        const val ACTION_DOWNLOAD = "com.spotipremium.DOWNLOAD"
        const val ACTION_DOWNLOAD_SONG = "com.spotipremium.DOWNLOAD_SONG"
        const val ACTION_REDOWNLOAD_SONG = "com.spotipremium.REDOWNLOAD_SONG"
        const val ACTION_DOWNLOAD_SELECTED = "com.spotipremium.DOWNLOAD_SELECTED"
        const val ACTION_DOWNLOAD_FINISHED = "com.spotipremium.DOWNLOAD_FINISHED"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1001

        private val _globalProgress = MutableStateFlow(DownloadProgress())
        val progress: StateFlow<DownloadProgress> = _globalProgress
    }
}
