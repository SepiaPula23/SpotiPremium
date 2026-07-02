package com.spotipremium

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.spotipremium.api.RemoteLogger
import com.spotipremium.data.AppDatabase
import com.spotipremium.data.Song
import com.spotipremium.service.DownloadService
import com.spotipremium.service.PlayerService
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class PlayerActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db by lazy { AppDatabase.get(this) }

    private var playlistId: String = ""
    private var playlistName: String = ""
    private var allSongs = listOf<Song>()
    private lateinit var songAdapter: SongAdapter
    private var isPlaying = false
    private var currentQueue = mutableListOf<Song>()
    private var currentIdx = 0
    private var pollJob: Job? = null
    private var downloadJob: Job? = null
    private var seekJob: Job? = null
    private var isSeeking = false
    private var playerDurationMs: Long = 1
    private var needSeekReset = AtomicBoolean(false)
    private var shuffleMode = false
    private var similarMode = false
    private var isDownloading = false
    private var isSelectMode = false
    private val selectedIds = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        requestNotificationPermission()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
            insets
        }

        playlistId = intent.getStringExtra("playlistId") ?: ""
        playlistName = intent.getStringExtra("playlistName") ?: ""

        findViewById<TextView>(R.id.playlistTitle).text = playlistName
        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }

        findViewById<Button>(R.id.dlBtn).setOnClickListener {
            if (!isDownloading) startDownloadAll()
            else Toast.makeText(this, "Ya descargando...", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.redownloadBtn).setOnClickListener { startRedownload() }
        findViewById<Button>(R.id.selectBtn).setOnClickListener { toggleSelectMode() }
        findViewById<Button>(R.id.redownloadSelectedBtn).setOnClickListener { startRedownloadSelected() }

        findViewById<ImageButton>(R.id.shuffleBtn).setOnClickListener { toggleShuffle() }
        findViewById<ImageButton>(R.id.similarBtn).setOnClickListener { toggleSimilar() }

        findViewById<ImageButton>(R.id.prevBtn).setOnClickListener { playPrev() }
        findViewById<ImageButton>(R.id.playPauseBtn).setOnClickListener { togglePlayPause() }
        findViewById<ImageButton>(R.id.nextBtn).setOnClickListener { playNext() }

        findViewById<ImageButton>(R.id.volDownBtn).setOnClickListener { adjustGain(-0.2f) }
        findViewById<ImageButton>(R.id.volUpBtn).setOnClickListener { adjustGain(0.2f) }

        val seekBar = findViewById<SeekBar>(R.id.seekBar)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val sec = (p.toLong() * playerDurationMs) / 1000L
                    findViewById<TextView>(R.id.currentTime).text = formatTime(sec)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isSeeking = false
                val pct = sb?.progress ?: 0
                startService(Intent(this@PlayerActivity, PlayerService::class.java).apply {
                    action = PlayerService.ACTION_SEEK
                    putExtra("progress", pct)
                })
            }
        })

        val recycler = findViewById<RecyclerView>(R.id.songRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter()
        recycler.adapter = songAdapter

        val filter = IntentFilter().apply {
            addAction(PlayerService.ACTION_SONG_CHANGED)
            addAction(PlayerService.ACTION_POSITION_UPDATE)
            addAction(PlayerService.ACTION_PLAY_STATE_CHANGED)
            addAction(PlayerService.ACTION_AUTO_NEXT)
            addAction(DownloadService.ACTION_DOWNLOAD_FINISHED)
            addAction("com.spotipremium.DEBUG")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(appReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(appReceiver, filter)
        }

        loadSongs()
        startSeekPolling()
    }

    private val appReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PlayerService.ACTION_SONG_CHANGED -> {
                    val songId = intent.getStringExtra("songId") ?: ""
                    val song = allSongs.find { it.id == songId } ?: return
                    currentIdx = currentQueue.indexOfFirst { it.id == songId }
                    if (currentIdx < 0) {
                        currentQueue.clear()
                        currentQueue.addAll(allSongs.filter { it.downloaded })
                        currentIdx = currentQueue.indexOfFirst { it.id == songId }
                    }
                    findViewById<LinearLayout>(R.id.playerBar).visibility = View.VISIBLE
                    updatePlayerUI(song)
                }
                PlayerService.ACTION_POSITION_UPDATE -> {
                    val dur = intent.getLongExtra("duration", 1L)
                    if (dur > 0) playerDurationMs = dur
                    val pos = intent.getLongExtra("position", 0L)
                    findViewById<TextView>(R.id.totalTime).text = formatTime(dur)
                    findViewById<TextView>(R.id.currentTime).text = formatTime(pos)
                    val pct = if (dur > 0) ((pos.toFloat() / dur) * 1000).toInt() else 0
                    findViewById<SeekBar>(R.id.seekBar).progress = pct.coerceIn(0, 1000)
                    if (needSeekReset.getAndSet(false)) {
                        findViewById<SeekBar>(R.id.seekBar).progress = 0
                        findViewById<TextView>(R.id.currentTime).text = "0:00"
                    }
                }
                PlayerService.ACTION_PLAY_STATE_CHANGED -> {
                    val playing = intent.getBooleanExtra("playing", false)
                    isPlaying = playing
                    findViewById<ImageButton>(R.id.playPauseBtn).setImageResource(
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play
                    )
                }
                DownloadService.ACTION_DOWNLOAD_FINISHED -> {
                    isDownloading = false
                }
                PlayerService.ACTION_AUTO_NEXT -> {
                    RemoteLogger.log("PlayerAct", "ACTION_AUTO_NEXT received, calling playNext()")
                    playNext()
                }
                "com.spotipremium.DEBUG" -> {
                    // Silent - logs go to server via RemoteLogger
                }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "$min:${sec.toString().padStart(2, '0')}"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    private fun loadSongs() {
        scope.launch {
            try {
                val songs = db.songDao().getByPlaylist(playlistId)
                allSongs = songs
                withContext(Dispatchers.Main) {
                    songAdapter.update(songs)
                    updateProgress()
                    updateDownloadButtons()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PlayerActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startDownloadAll() {
        isDownloading = true
        startForegroundService(Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_DOWNLOAD
            putExtra("playlistId", playlistId)
            putExtra("playlistName", playlistName)
        })
        startPolling()
    }

    private fun startDownloadSingle(song: Song) {
        RemoteLogger.log("PlayerAct", "startDownloadSingle(${song.name}), downloaded=${song.downloaded}, localPath=${song.localPath}")
        Toast.makeText(this, "Descargando ${song.name}...", Toast.LENGTH_SHORT).show()
        val action = if (song.downloaded) DownloadService.ACTION_REDOWNLOAD_SONG else DownloadService.ACTION_DOWNLOAD_SONG
        startForegroundService(Intent(this, DownloadService::class.java).apply {
            this.action = action
            putExtra("songId", song.id)
            putExtra("playlistName", playlistName)
        })
        startPolling()
    }

    private fun startRedownload() {
        stopService(Intent(this, DownloadService::class.java))
        isDownloading = true
        scope.launch {
            val downloaded = db.songDao().getByPlaylist(playlistId).filter { it.downloaded }
            for (song in downloaded) {
                if (song.localPath.isNotBlank()) {
                    if (song.localPath.startsWith("content://")) {
                        try { contentResolver.delete(Uri.parse(song.localPath), null, null) } catch (_: Exception) {}
                    } else {
                        try { File(song.localPath).delete() } catch (_: Exception) {}
                    }
                }
                db.songDao().markNotDownloaded(song.id)
            }
            withContext(Dispatchers.Main) {
                loadSongs()
                startDownloadAll()
            }
        }
    }

    private fun toggleSelectMode() {
        isSelectMode = !isSelectMode
        if (!isSelectMode) selectedIds.clear()
        findViewById<Button>(R.id.redownloadSelectedBtn).visibility = View.GONE
        songAdapter.notifyDataSetChanged()
        updateSelectBtnText()
    }

    private fun updateSelectBtnText() {
        findViewById<Button>(R.id.selectBtn).text =
            if (isSelectMode) "Cancelar" else "Seleccionar"
    }

    private fun startRedownloadSelected() {
        if (selectedIds.isEmpty()) return
        if (isDownloading) {
            Toast.makeText(this, "Ya descargando...", Toast.LENGTH_SHORT).show()
            return
        }
        isDownloading = true
        stopService(Intent(this, DownloadService::class.java))
        scope.launch {
            for (id in selectedIds) {
                val song = db.songDao().getById(id) ?: continue
                if (song.localPath.isNotBlank()) {
                    if (song.localPath.startsWith("content://")) {
                        try { contentResolver.delete(Uri.parse(song.localPath), null, null) } catch (_: Exception) {}
                    } else {
                        try { File(song.localPath).delete() } catch (_: Exception) {}
                    }
                }
                db.songDao().markNotDownloaded(id)
            }
            withContext(Dispatchers.Main) {
                loadSongs()
                startForegroundService(Intent(this@PlayerActivity, DownloadService::class.java).apply {
                    action = DownloadService.ACTION_DOWNLOAD_SELECTED
                    putStringArrayListExtra("songIds", ArrayList(selectedIds))
                    putExtra("playlistName", playlistName)
                })
                startPolling()
                selectedIds.clear()
                isSelectMode = false
                updateSelectBtnText()
            }
        }
    }

    private fun showUrlDialog(song: Song) {
        val input = EditText(this).apply {
            setText(song.youtubeUrl)
            hint = "https://youtube.com/watch?v=..."
            setSingleLine()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("URL personalizada para ${song.name}")
            .setMessage("Pega la URL de YouTube y se descargará automáticamente")
            .setView(input)
            .setPositiveButton("Descargar") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                scope.launch {
                    db.songDao().updateYoutubeUrl(song.id, url)
                    if (isDownloading) return@launch
                    isDownloading = true
                    val action = if (song.downloaded) DownloadService.ACTION_REDOWNLOAD_SONG else DownloadService.ACTION_DOWNLOAD_SONG
                    withContext(Dispatchers.Main) {
                        startForegroundService(Intent(this@PlayerActivity, DownloadService::class.java).apply {
                            this.action = action
                            putExtra("songId", song.id)
                            putExtra("playlistName", playlistName)
                        })
                        startPolling()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showSongOptions(song: Song) {
        val items = mutableListOf<String>().apply {
            if (song.downloaded) add("Recortar canción")
            if (song.downloaded) add("Re-descargar")
            add("URL personalizada")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(song.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Recortar canción" -> showTrimDialog(song)
                    "Re-descargar" -> {
                        Toast.makeText(this, "Re-descargando ${song.name}", Toast.LENGTH_SHORT).show()
                        startDownloadSingle(song)
                    }
                    "URL personalizada" -> showUrlDialog(song)
                }
            }
            .show()
    }

    private fun showTrimDialog(song: Song) {
        val startInput = EditText(this).apply {
            hint = "Inicio (mm:ss)"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val endInput = EditText(this).apply {
            hint = "Fin (mm:ss)"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), 0, dpToPx(16), 0)
            addView(startInput)
            addView(endInput)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Recortar ${song.name}")
            .setView(layout)
            .setPositiveButton("Recortar") { _, _ ->
                val startStr = startInput.text.toString().trim()
                val endStr = endInput.text.toString().trim()
                val startSec = parseTimeToSeconds(startStr)
                val endSec = parseTimeToSeconds(endStr)
                if (startSec < 0 || endSec <= startSec) {
                    Toast.makeText(this, "Tiempos inválidos. Usa mm:ss", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                trimSong(song, startSec, endSec)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun parseTimeToSeconds(time: String): Int {
        val parts = time.split(":")
        return when (parts.size) {
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            1 -> parts[0].toIntOrNull() ?: 0
            else -> -1
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun trimSong(song: Song, startSec: Int, endSec: Int) {
        val path = song.localPath
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(this, "Recortar requiere Android 8+", Toast.LENGTH_LONG).show()
            return
        }
        if (!path.endsWith(".m4a") && !path.contains("m4a")) {
            // Try anyway but warn
        }
        Toast.makeText(this, "Recortando ${song.name}...", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val inputUri = Uri.parse(song.localPath)
                val tempFile = File(cacheDir, "trim_temp_${song.id}.m4a")

                val trimOk = withContext(Dispatchers.IO) {
                    trimMedia(inputUri, tempFile.absolutePath, startSec, endSec)
                }
                if (!trimOk) {
                    tempFile.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PlayerActivity, "Error: formato no compatible (solo m4a)", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val fileName = "${sanitize(song.artist)} - ${sanitize(song.name)} (recortada).m4a"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                        put(MediaStore.Audio.Media.TITLE, "${song.name} (recortada)")
                        put(MediaStore.Audio.Media.ARTIST, song.artist)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Recortadas")
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                    val outputUri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    if (outputUri == null) {
                        tempFile.delete()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@PlayerActivity, "Error al guardar en MediaStore", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    contentResolver.openOutputStream(outputUri)?.use { out ->
                        tempFile.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    contentResolver.update(outputUri, values, null, null)
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Recortadas")
                    dir.mkdirs()
                    val dest = File(dir, fileName)
                    tempFile.copyTo(dest, overwrite = true)
                }
                tempFile.delete()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PlayerActivity, "Recorte guardado en Music/Recortadas", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                RemoteLogger.log("PlayerAct", "trimSong exception: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PlayerActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun trimMedia(inputUri: Uri, outputPath: String, startSec: Int, endSec: Int): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            extractor = MediaExtractor()
            extractor!!.setDataSource(this, inputUri, null)

            var audioTrackIndex = -1
            for (i in 0 until extractor!!.trackCount) {
                val format = extractor!!.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }
            if (audioTrackIndex < 0) {
                RemoteLogger.log("PlayerAct", "trimMedia: no audio track found")
                extractor!!.release()
                return false
            }

            extractor!!.selectTrack(audioTrackIndex)
            val trackFormat = extractor!!.getTrackFormat(audioTrackIndex)

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer!!.addTrack(trackFormat)
            muxer!!.start()

            extractor!!.seekTo(startSec * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val buf = ByteBuffer.allocate(1024 * 1024)
            val endMicros = endSec * 1000L * 1000L
            val startMicros = startSec * 1000L * 1000L
            val info = MediaCodec.BufferInfo()
            var samplesWritten = 0

            while (true) {
                val sampleTime = extractor!!.sampleTime
                if (sampleTime < 0 || sampleTime > endMicros) break

                val size = extractor!!.readSampleData(buf, 0)
                if (size < 0) break

                buf.rewind()
                info.offset = 0
                info.size = size
                info.presentationTimeUs = sampleTime - startMicros
                info.flags = extractor!!.sampleFlags

                muxer!!.writeSampleData(muxerTrackIndex, buf, info)
                samplesWritten++
                extractor!!.advance()
            }

            if (samplesWritten == 0) {
                RemoteLogger.log("PlayerAct", "trimMedia: no samples written")
                muxer!!.stop()
                muxer!!.release()
                extractor!!.release()
                return false
            }

            muxer!!.stop()
            muxer!!.release()
            extractor!!.release()
            RemoteLogger.log("PlayerAct", "trimMedia OK: $samplesWritten samples, ${endSec - startSec}s")
            true
        } catch (e: Exception) {
            RemoteLogger.log("PlayerAct", "trimMedia exception: ${e.message}")
            try { extractor?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            false
        }
    }

    private fun sanitize(name: String): String {
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .trim()
            .take(200)
    }

    private fun adjustGain(delta: Float) {
        val currentSong = currentQueue.getOrNull(currentIdx) ?: return
        val newGain = (currentSong.gain + delta).coerceIn(0.2f, 2.0f)
        // Update local copy
        val idx = allSongs.indexOfFirst { it.id == currentSong.id }
        if (idx >= 0) {
            allSongs = allSongs.toMutableList().apply {
                this[idx] = this[idx].copy(gain = newGain)
            }
        }
        currentSong.let { s ->
            currentQueue[currentIdx] = s.copy(gain = newGain)
        }
        // Save to DB
        scope.launch {
            db.songDao().updateGain(currentSong.id, newGain)
        }
        // Apply via new ACTION_PLAY with updated gain
        val path = currentSong.localPath
        if (path.startsWith("content://") || File(path).exists()) {
            startService(Intent(this, PlayerService::class.java).apply {
                action = PlayerService.ACTION_PLAY
                putExtra("path", path)
                putExtra("name", currentSong.name)
                putExtra("artist", currentSong.artist)
                putExtra("songId", currentSong.id)
                putExtra("playlistId", playlistId)
                putExtra("gain", newGain)
            })
        }
        val label = if (delta > 0) "Volumen +" else "Volumen -"
        Toast.makeText(this, "$label: ${"%.0f".format(newGain * 100)}%", Toast.LENGTH_SHORT).show()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                delay(2000)
                val songs = db.songDao().getByPlaylist(playlistId)
                allSongs = songs
                withContext(Dispatchers.Main) {
                    songAdapter.update(songs)
                    updateProgress()
                    updateDownloadButtons()
                    if (songs.all { it.downloaded }) {
                        findViewById<Button>(R.id.dlBtn).visibility = View.GONE
                        findViewById<ProgressBar>(R.id.downloadProgressBar).visibility = View.GONE
                        isDownloading = false
                        pollJob?.cancel()
                    }
                }
            }
        }
        downloadJob = scope.launch {
            DownloadService.progress.collect { p ->
                withContext(Dispatchers.Main) {
                    val bar = findViewById<ProgressBar>(R.id.downloadProgressBar)
                    val info = findViewById<TextView>(R.id.downloadInfo)
                    if (p.finished) {
                        bar.visibility = View.GONE
                        isDownloading = false
                        if (p.failed > 0) {
                            info.text = "${p.current} descargadas, ${p.failed} fallaron"
                        } else {
                            info.text = "Descarga completada"
                        }
                    } else if (p.total > 0) {
                        bar.visibility = View.VISIBLE
                        bar.max = p.total
                        bar.progress = p.current
                        info.text = "${p.current}/${p.total} - ${p.songName}"
                    }
                }
            }
        }
    }

    private fun updateProgress() {
        val downloaded = allSongs.count { it.downloaded }
        findViewById<TextView>(R.id.downloadInfo).text = "$downloaded/${allSongs.size} descargadas"
    }

    private fun updateDownloadButtons() {
        val allDownloaded = allSongs.all { it.downloaded }
        val anyDownloaded = allSongs.any { it.downloaded }
        findViewById<Button>(R.id.dlBtn).visibility =
            if (allDownloaded) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.redownloadBtn).visibility =
            if (anyDownloaded && !isSelectMode) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.selectBtn).visibility =
            if (anyDownloaded) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.redownloadSelectedBtn).visibility =
            if (isSelectMode && selectedIds.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun startSeekPolling() {
        seekJob?.cancel()
        seekJob = scope.launch {
            while (true) {
                delay(500)
                startService(Intent(this@PlayerActivity, PlayerService::class.java).apply {
                    action = PlayerService.ACTION_QUERY_POSITION
                })
            }
        }
    }

    private fun togglePlayPause() {
        isPlaying = !isPlaying
        findViewById<ImageButton>(R.id.playPauseBtn).setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        startService(Intent(this, PlayerService::class.java).apply {
            action = if (isPlaying) PlayerService.ACTION_RESUME else PlayerService.ACTION_PAUSE
        })
    }

    private fun playSong(song: Song) {
        val path = song.localPath
        RemoteLogger.log("PlayerAct", "playSong(${song.name}), path=$path, shuffle=$shuffleMode, similar=$similarMode, queueSize=${currentQueue.size}")
        if (path.isBlank()) {
            RemoteLogger.log("PlayerAct", "playSong: path is BLANK for ${song.name}")
            return
        }
        if (!path.startsWith("content://")) {
            val file = File(path)
            if (!file.exists()) {
                RemoteLogger.log("PlayerAct", "playSong: file NOT FOUND at $path")
                return
            }
        }

        buildQueue()
        currentIdx = currentQueue.indexOfFirst { it.id == song.id }
        if (currentIdx < 0) return

        needSeekReset.set(true)
        findViewById<SeekBar>(R.id.seekBar).progress = 0
        findViewById<TextView>(R.id.currentTime).text = "0:00"
        findViewById<TextView>(R.id.totalTime).text = "0:00"

        startService(Intent(this, PlayerService::class.java).apply {
            action = PlayerService.ACTION_PLAY
            putExtra("path", path)
            putExtra("name", song.name)
            putExtra("artist", song.artist)
            putExtra("songId", song.id)
            putExtra("playlistId", playlistId)
            putExtra("gain", song.gain)
        })

        isPlaying = true
        findViewById<ImageButton>(R.id.playPauseBtn).setImageResource(R.drawable.ic_pause)
        findViewById<LinearLayout>(R.id.playerBar).visibility = View.VISIBLE
        updatePlayerUI(song)
    }

    private fun buildQueue() {
        val downloaded = allSongs.filter { it.downloaded }
        currentQueue = if (shuffleMode) downloaded.shuffled().toMutableList()
        else downloaded.toMutableList()
    }

    private fun playNext() {
        if (currentQueue.isEmpty()) {
            RemoteLogger.log("PlayerAct", "playNext: queue is EMPTY")
            return
        }
        currentIdx++
        RemoteLogger.log("PlayerAct", "playNext: idx=$currentIdx, queueSize=${currentQueue.size}, similar=$similarMode")
        if (currentIdx >= currentQueue.size) {
            if (similarMode) { buildQueue(); currentIdx = 0 }
            else { currentIdx = 0 }
        }
        playSong(currentQueue[currentIdx])
    }

    private fun playPrev() {
        if (currentQueue.isEmpty()) return
        currentIdx--
        if (currentIdx < 0) currentIdx = currentQueue.size - 1
        playSong(currentQueue[currentIdx])
    }

    private fun toggleShuffle() {
        shuffleMode = !shuffleMode
        if (shuffleMode) similarMode = false
        updateModeUI()
        if (isPlaying) buildQueue()
    }

    private fun toggleSimilar() {
        similarMode = !similarMode
        if (similarMode) shuffleMode = false
        updateModeUI()
    }

    private fun updateModeUI() {
        findViewById<ImageButton>(R.id.shuffleBtn).setColorFilter(
            if (shuffleMode) 0xFF1DB954.toInt() else 0xFFB3B3B3.toInt()
        )
        findViewById<ImageButton>(R.id.similarBtn).setColorFilter(
            if (similarMode) 0xFF1DB954.toInt() else 0xFFB3B3B3.toInt()
        )
        findViewById<TextView>(R.id.modeLabel).text = when {
            shuffleMode -> "Reproducción aleatoria"
            similarMode -> "Reproducción por similitud"
            else -> "Reproducción secuencial"
        }
    }

    private fun updatePlayerUI(song: Song) {
        findViewById<TextView>(R.id.nowPlaying).text = "${song.artist} - ${song.name}"
        findViewById<TextView>(R.id.queueInfo).text =
            if (shuffleMode) "?" else "${currentIdx + 1}/${currentQueue.size}"
    }

    private inner class SongAdapter : RecyclerView.Adapter<SongAdapter.Holder>() {
        private var items = listOf<Song>()
        fun update(list: List<Song>) { items = list; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_song_select, parent, false))
        }

        override fun onBindViewHolder(h: Holder, i: Int) {
            val s = items[i]
            h.name.text = s.name
            h.artist.text = s.artist

            // Selection mode
            h.selectCheck.visibility = if (isSelectMode) View.VISIBLE else View.GONE
            h.selectCheck.isChecked = selectedIds.contains(s.id)
            h.itemView.setBackgroundColor(
                if (isSelectMode && selectedIds.contains(s.id)) 0xFF2A5C2A.toInt()
                else 0x00000000.toInt()
            )

            if (s.downloaded) {
                h.status.setImageResource(R.drawable.ic_downloaded)
                h.status.visibility = View.VISIBLE
                h.downloadBtn.setImageResource(R.drawable.ic_play)
                h.downloadBtn.visibility = View.VISIBLE
                h.downloadBtn.setOnClickListener {
                    if (isSelectMode) toggleSelection(s.id)
                    else playSong(s)
                }
                h.downloadBtn.setOnLongClickListener {
                    if (!isSelectMode) {
                        showSongOptions(s)
                    }
                    true
                }
                h.itemView.setOnClickListener {
                    if (isSelectMode) toggleSelection(s.id)
                    else playSong(s)
                }
                h.itemView.setOnLongClickListener {
                    if (!isSelectMode) {
                        showSongOptions(s)
                        true
                    } else false
                }
            } else {
                h.status.visibility = View.INVISIBLE
                h.downloadBtn.setImageResource(R.drawable.ic_download)
                h.downloadBtn.visibility = View.VISIBLE
                h.downloadBtn.setOnClickListener {
                    if (isSelectMode) toggleSelection(s.id)
                    else startDownloadSingle(s)
                }
                h.itemView.setOnClickListener {
                    if (isSelectMode) toggleSelection(s.id)
                    else startDownloadSingle(s)
                }
                h.itemView.setOnLongClickListener {
                    if (!isSelectMode) {
                        showSongOptions(s)
                        true
                    } else false
                }
            }
        }

        private fun toggleSelection(id: String) {
            if (selectedIds.contains(id)) selectedIds.remove(id)
            else selectedIds.add(id)
            notifyDataSetChanged()
            updateDownloadButtons()
        }

        override fun getItemCount() = items.size

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val selectCheck = v.findViewById<CheckBox>(R.id.selectCheck)!!
            val name = v.findViewById<TextView>(R.id.songName)!!
            val artist = v.findViewById<TextView>(R.id.songArtist)!!
            val status = v.findViewById<ImageView>(R.id.statusIcon)!!
            val downloadBtn = v.findViewById<ImageButton>(R.id.downloadBtn)!!
        }
    }

    override fun onResume() {
        super.onResume()
        isDownloading = false
    }

    override fun onDestroy() {
        scope.cancel()
        pollJob?.cancel()
        downloadJob?.cancel()
        seekJob?.cancel()
        try { unregisterReceiver(appReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
