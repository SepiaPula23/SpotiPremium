package com.spotipremium.service

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import com.spotipremium.api.RemoteLogger
import com.spotipremium.data.AppDatabase
import com.spotipremium.data.Song
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*

class PlayerService : Service() {

    private lateinit var player: ExoPlayer
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var currentSongName = "Potify"
    private var currentSongId = ""
    private var currentPlaylistId = ""
    private var isPlaying = false
    private var hasMediaItem = false
    private var currentGain = 1.0f
    private var notificationManager: NotificationManager? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db by lazy { AppDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        val ip = prefs.getString("serverIp", "") ?: ""
        RemoteLogger.init(this, if (ip.isBlank()) "" else "http://$ip:8000")

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                RemoteLogger.log("Player", "State=${
                    when (state) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "$state"
                    }
                }, playWhenReady=${player.playWhenReady}, isPlaying=$isPlaying")
                if (state == Player.STATE_ENDED) {
                    if (currentPlaylistId.isBlank()) {
                        // STATE_ENDED triggered by stop()/clearMediaItems() during song change
                    } else {
                        isPlaying = false
                        playNextInPlaylist()
                    }
                }
                if (state == Player.STATE_READY && isPlaying) {
                    player.play()
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playNextInPlaylist()
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        RemoteLogger.log("Player", "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_PLAY -> {
                val prefs = getSharedPreferences("app", MODE_PRIVATE)
                val ip = prefs.getString("serverIp", "") ?: ""
                RemoteLogger.refreshUrl(if (ip.isBlank()) "" else "http://$ip:8000")
                val path = intent.getStringExtra("path") ?: return START_NOT_STICKY
                val name = intent.getStringExtra("name") ?: ""
                val artist = intent.getStringExtra("artist") ?: ""
                val songId = intent.getStringExtra("songId") ?: ""
                val playlistId = intent.getStringExtra("playlistId") ?: ""
                val gain = intent.getFloatExtra("gain", 1.0f)
                playSong(path, name, artist, songId, playlistId, gain)
            }
            ACTION_PAUSE -> doPause()
            ACTION_RESUME -> {
                if (!hasMediaItem) {
                    sendPlayStateBroadcast(false)
                    return START_STICKY
                }
                doPlay()
            }
            ACTION_STOP -> doStop()
            ACTION_SEEK -> {
                val progress = intent.getIntExtra("progress", 0)
                val dur = if (player.duration > 0) player.duration else 1L
                player.seekTo((progress.toLong() * dur) / 1000L)
            }
            ACTION_QUERY_POSITION -> {
                val pos = player.currentPosition
                val dur = if (player.duration > 0) player.duration else 1L
                sendBroadcast(Intent(ACTION_POSITION_UPDATE).apply {
                    putExtra("position", pos)
                    putExtra("duration", dur)
                    setPackage(packageName)
                })
                if (hasMediaItem && dur > 1000) {
                    updateNotificationPosition(pos, dur)
                }
            }
            ACTION_NOTIFY_PLAY -> {
                if (!hasMediaItem) return START_STICKY
                doPlay()
            }
            ACTION_NOTIFY_PAUSE -> doPause()
            ACTION_NOTIFY_NEXT -> playNextInPlaylist()
            ACTION_NOTIFY_PREV -> playPrevInPlaylist()
        }
        return START_STICKY
    }

    private fun doPlay() {
        RemoteLogger.log("Player", "doPlay() called, playWhenReady=${player.playWhenReady}, state=${player.playbackState}")
        isPlaying = true
        requestAudioFocus()
        player.volume = currentGain.coerceIn(0.0f, 2.0f)
        player.play()
        showMediaNotification(currentSongName, true)
        sendPlayStateBroadcast(true)
    }

    private fun doPause() {
        isPlaying = false
        player.pause()
        abandonAudioFocus()
        showMediaNotification("En pausa", false)
        sendPlayStateBroadcast(false)
    }

    private fun doStop() {
        isPlaying = false
        hasMediaItem = false
        currentPlaylistId = ""
        currentSongId = ""
        player.stop()
        abandonAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun playSong(path: String, name: String, artist: String, songId: String, playlistId: String, gain: Float) {
        RemoteLogger.log("Player", "playSong() called for $artist - $name (playlist=$playlistId)")
        currentSongId = songId
        currentSongName = "$artist - $name"
        currentGain = gain
        hasMediaItem = true
        // Set playlist to blank BEFORE stop() to prevent spurious STATE_ENDED triggering playNextInPlaylist
        currentPlaylistId = ""
        isPlaying = false
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(MediaItem.fromUri(path))
        player.prepare()
        doPlay()
        Handler(mainLooper).postDelayed({
            currentPlaylistId = playlistId
            if (isPlaying && !player.isPlaying) {
                RemoteLogger.log("Player", "Forced play after delay - was not playing")
                player.play()
            }
        }, 300)
        sendBroadcast(Intent(ACTION_SONG_CHANGED).apply {
            putExtra("songId", songId)
            putExtra("songName", currentSongName)
            setPackage(packageName)
        })
    }

    private fun playNextInPlaylist() {
        RemoteLogger.log("Player", "playNextInPlaylist() called, playlist=$currentPlaylistId")
        if (currentPlaylistId.isBlank()) {
            RemoteLogger.log("Player", "playNextInPlaylist: currentPlaylistId is BLANK, discarding")
            return
        }
        // Signal PlayerActivity to play next in its queue (respects shuffle/similar mode)
        sendBroadcast(Intent(ACTION_AUTO_NEXT).apply {
            setPackage(packageName)
        })
        // Fallback: if PlayerActivity doesn't respond within 500ms, play next directly from DB
        Handler(mainLooper).postDelayed({
            if (!isPlaying) {
                RemoteLogger.log("Player", "playNextInPlaylist fallback: Activity unresponsive, playing from DB")
                val savedPlaylist = currentPlaylistId
                val savedSongId = currentSongId
                ioScope.launch {
                    val songs = db.songDao().getByPlaylist(savedPlaylist)
                        .filter { it.downloaded && it.localPath.isNotBlank() }
                    if (songs.isEmpty()) return@launch
                    val idx = songs.indexOfFirst { it.id == savedSongId }
                    if (idx < 0) return@launch
                    val nextIdx = if (idx + 1 < songs.size) idx + 1 else 0
                    val next = songs[nextIdx]
                    Handler(mainLooper).post {
                        startService(Intent(this@PlayerService, PlayerService::class.java).apply {
                            action = ACTION_PLAY
                            putExtra("path", next.localPath)
                            putExtra("name", next.name)
                            putExtra("artist", next.artist)
                            putExtra("songId", next.id)
                            putExtra("playlistId", next.playlistId)
                            putExtra("gain", next.gain)
                        })
                    }
                }
            }
        }, 500)
    }

    private fun playPrevInPlaylist() {
        if (currentPlaylistId.isBlank()) return
        ioScope.launch {
            val songs = db.songDao().getByPlaylist(currentPlaylistId)
                .filter { it.downloaded && it.localPath.isNotBlank() }
            if (songs.isEmpty()) return@launch
            val idx = songs.indexOfFirst { it.id == currentSongId }
            if (idx < 0) return@launch
            val prevIdx = if (idx - 1 >= 0) idx - 1 else songs.size - 1
            val prev = songs[prevIdx]
            Handler(mainLooper).post {
                startService(Intent(this@PlayerService, PlayerService::class.java).apply {
                    action = ACTION_PLAY
                    putExtra("path", prev.localPath)
                    putExtra("name", prev.name)
                    putExtra("artist", prev.artist)
                    putExtra("songId", prev.id)
                    putExtra("playlistId", prev.playlistId)
                    putExtra("gain", prev.gain)
                })
            }
        }
    }

    private fun sendPlayStateBroadcast(playing: Boolean) {
        sendBroadcast(Intent(ACTION_PLAY_STATE_CHANGED).apply {
            putExtra("playing", playing)
            putExtra("songName", currentSongName)
            setPackage(packageName)
        })
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        doPause()
                    }
                }
                .build()
            audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "$min:${sec.toString().padStart(2, '0')}"
    }

    private fun updateNotificationPosition(pos: Long, dur: Long) {
        val text = "${currentSongName} - ${formatTime(pos)} / ${formatTime(dur)}"
        val notification = buildNotification(text, isPlaying)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun showMediaNotification(text: String, playing: Boolean) {
        val notification = buildNotification(text, playing)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String, playing: Boolean): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE)

        val prevIntent = PendingIntent.getService(this, 10,
            Intent(this, PlayerService::class.java).apply { action = ACTION_NOTIFY_PREV },
            PendingIntent.FLAG_IMMUTABLE)
        val playIntent = PendingIntent.getService(this, 11,
            Intent(this, PlayerService::class.java).apply { action = ACTION_NOTIFY_PLAY },
            PendingIntent.FLAG_IMMUTABLE)
        val pauseIntent = PendingIntent.getService(this, 12,
            Intent(this, PlayerService::class.java).apply { action = ACTION_NOTIFY_PAUSE },
            PendingIntent.FLAG_IMMUTABLE)
        val nextIntent = PendingIntent.getService(this, 13,
            Intent(this, PlayerService::class.java).apply { action = ACTION_NOTIFY_NEXT },
            PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 14,
            Intent(this, PlayerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE)

        val playBtnIntent = if (playing) pauseIntent else playIntent
        val playIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playLabel = if (playing) "Pausar" else "Reanudar"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (playing) currentSongName else "Potify")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setStyle(MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setCancelButtonIntent(stopIntent))
            .addAction(android.R.drawable.ic_media_previous, "Anterior", prevIntent)
            .addAction(playIcon, playLabel, playBtnIntent)
            .addAction(android.R.drawable.ic_media_next, "Siguiente", nextIntent)
            .setDeleteIntent(stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Reproducción", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controles de reproducción"
                setShowBadge(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        abandonAudioFocus()
        ioScope.cancel()
        player.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "com.spotipremium.PLAY"
        const val ACTION_PAUSE = "com.spotipremium.PAUSE"
        const val ACTION_RESUME = "com.spotipremium.RESUME"
        const val ACTION_STOP = "com.spotipremium.STOP"
        const val ACTION_SEEK = "com.spotipremium.SEEK"
        const val ACTION_NOTIFY_PLAY = "com.spotipremium.NOTIFY_PLAY"
        const val ACTION_NOTIFY_PAUSE = "com.spotipremium.NOTIFY_PAUSE"
        const val ACTION_NOTIFY_NEXT = "com.spotipremium.NOTIFY_NEXT"
        const val ACTION_NOTIFY_PREV = "com.spotipremium.NOTIFY_PREV"
        const val ACTION_NEXT_SONG = "com.spotipremium.NEXT_SONG"
        const val ACTION_PREV_SONG = "com.spotipremium.PREV_SONG"
        const val ACTION_QUERY_POSITION = "com.spotipremium.QUERY_POSITION"
        const val ACTION_POSITION_UPDATE = "com.spotipremium.POSITION_UPDATE"
        const val ACTION_PLAY_STATE_CHANGED = "com.spotipremium.PLAY_STATE_CHANGED"
        const val ACTION_SONG_CHANGED = "com.spotipremium.SONG_CHANGED"
        const val ACTION_AUTO_NEXT = "com.spotipremium.AUTO_NEXT"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1002
    }
}
