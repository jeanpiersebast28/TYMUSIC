package com.tymusiclite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.net.HttpURLConnection
import java.net.URL

private const val SERVICE_TAG = "TYMusicLite"

class PlaybackService : Service() {

    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        setupMediaSession()
        acquireLocks()
    }

    private fun acquireLocks() {
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TYMusicLite::playback").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(SERVICE_TAG, "wakelock failed", e)
        }
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TYMusicLite::wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(SERVICE_TAG, "wifilock failed", e)
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_PLAY_PAUSE -> runWebViewCommand(COMMAND_PLAY_PAUSE)
            ACTION_NEXT -> runWebViewCommand(COMMAND_NEXT)
            ACTION_PREVIOUS -> runWebViewCommand(COMMAND_PREVIOUS)
        }
        promoteToForeground()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        taskRemoved = true
        runWebViewCommand(COMMAND_PAUSE_MEDIA)
        WebViewHolder.detachAndDestroyWebView()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        releaseLocks()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    private fun setupMediaSession() {
        val session = MediaSessionCompat(this, "TYMusicLiteSession")
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = onCommandExpectingState(true)
            override fun onPause() = onCommandExpectingState(false)
            override fun onSkipToNext() = runWebViewCommand(COMMAND_NEXT)
            override fun onSkipToPrevious() = runWebViewCommand(COMMAND_PREVIOUS)
            override fun onSeekTo(pos: Long) = runWebViewCommand(COMMAND_SEEK_PREFIX + pos)
        })
        session.isActive = true
        mediaSession = session
    }

    private fun onCommandExpectingState(targetPlaying: Boolean) {
        if (isPlaying != targetPlaying) {
            runWebViewCommand(COMMAND_PLAY_PAUSE)
        }
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        updatePlaybackState()
    }

    private fun updatePlaybackState() {
        val session = mediaSession ?: return
        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        val positionKnown = positionMs > 0 || durationMs > 0
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY
                        or PlaybackStateCompat.ACTION_PAUSE
                        or PlaybackStateCompat.ACTION_PLAY_PAUSE
                        or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        or PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                    state,
                    if (positionKnown) {
                        positionMs.coerceAtLeast(0)
                    } else {
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
                    },
                    if (isPlaying) 1f else 0f,
                )
                .build()
        )
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(
                MediaMetadataCompat.METADATA_KEY_TITLE,
                trackTitle ?: getString(R.string.notif_title),
            )
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "YouTube Music")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs.coerceAtLeast(0))
        currentArtworkBitmap?.let { bitmap ->
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
        }
        artworkUrl?.let { url ->
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, url)
        }
        session.setMetadata(metadataBuilder.build())
    }

    private fun refreshMediaNotification() {
        updatePlaybackState()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun commandPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackService::class.java).putExtra(EXTRA_ACTION, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun contentPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun buildNotification(): Notification {
        val playing = isPlaying
        val toggleIcon = if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play
        val toggleLabel = getString(if (playing) R.string.action_pause else R.string.action_play)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF0033.toInt())
            .setContentTitle(trackTitle ?: getString(R.string.notif_title))
            .setContentText(artist ?: getString(R.string.notif_text))
            .setContentIntent(contentPendingIntent())
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_media_previous, getString(R.string.action_previous), commandPendingIntent(ACTION_PREVIOUS, 1))
            .addAction(toggleIcon, toggleLabel, commandPendingIntent(ACTION_PLAY_PAUSE, 2))
            .addAction(R.drawable.ic_media_next, getString(R.string.action_next), commandPendingIntent(ACTION_NEXT, 3))

        currentArtworkBitmap?.let { bitmap ->
            builder.setLargeIcon(bitmap)
        }

        mediaSession?.sessionToken?.let { token ->
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(token)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }

        return builder.build()
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.tymusiclite.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.tymusiclite.action.NEXT"
        const val ACTION_PREVIOUS = "com.tymusiclite.action.PREVIOUS"
        const val COMMAND_PLAY_PAUSE = "play_pause"
        const val COMMAND_NEXT = "next"
        const val COMMAND_PREVIOUS = "previous"
        const val COMMAND_SEEK_PREFIX = "seek:"
        const val COMMAND_PAUSE_MEDIA = "pause_media"

        @Volatile
        var taskRemoved: Boolean = false
            private set

        fun clearTaskRemoved() {
            taskRemoved = false
        }

        private const val EXTRA_ACTION = "action"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val STOP_DEBOUNCE_MS = 800L
        private const val MAX_ARTWORK_SIZE = 512

        @Volatile
        var isPlaying: Boolean = false
            private set

        @Volatile
        var trackTitle: String? = null
            private set

        @Volatile
        var artist: String? = null
            private set

        @Volatile
        var positionMs: Long = 0
            private set

        @Volatile
        var durationMs: Long = 0
            private set

        @Volatile
        private var artworkUrl: String? = null

        @Volatile
        private var currentArtworkBitmap: Bitmap? = null

        @Volatile
        private var activeDownloadUrl: String? = null

        @Volatile
        private var lastFailedArtworkUrl: String? = null

        private val mainHandler = Handler(Looper.getMainLooper())
        private var pendingStop: Runnable? = null
        private var instance: PlaybackService? = null

        fun updateState(context: Context, playing: Boolean, title: String?) {
            if (taskRemoved) {
                return
            }
            mainHandler.post {
                val appContext = context.applicationContext
                val stateChanged = isPlaying != playing
                val titleChanged = trackTitle != title
                isPlaying = playing
                trackTitle = title

                if (titleChanged && title != null) {
                    artist = null
                    artworkUrl = null
                    currentArtworkBitmap = null
                    positionMs = 0
                    durationMs = 0
                }

                cancelPendingStop()

                if (!stateChanged && !titleChanged) return@post

                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, PlaybackService::class.java),
                )

                if (!playing) scheduleStop(appContext)
            }
        }

        fun updateProgress(context: Context, posMs: Long, durMs: Long) {
            mainHandler.post {
                if (positionMs == posMs && durationMs == durMs) return@post
                positionMs = posMs
                durationMs = durMs
                instance?.refreshMediaNotification()
            }
        }

        fun updateArtwork(url: String) {
            if (url.isBlank()) return
            mainHandler.post {
                if (url != artworkUrl) {
                    artworkUrl = url
                    currentArtworkBitmap = null
                }
                if (currentArtworkBitmap == null && activeDownloadUrl != url) {
                    startArtworkDownload(url)
                }
            }
        }

        fun updateArtist(value: String) {
            if (value.isBlank()) return
            mainHandler.post {
                val parsed = value.split("•").firstOrNull()?.trim()
                    ?.ifBlank { null } ?: return@post
                if (artist != parsed) {
                    artist = parsed
                    instance?.refreshMediaNotification()
                }
            }
        }

        private fun startArtworkDownload(url: String) {
            if (url == lastFailedArtworkUrl && url == artworkUrl) return
            activeDownloadUrl = url
            Thread {
                val bitmap = try {
                    downloadBitmap(url)?.let { scaleDown(it, MAX_ARTWORK_SIZE) }
                } catch (e: Exception) {
                    Log.w(SERVICE_TAG, "artwork download failed", e)
                    null
                }
                mainHandler.post {
                    activeDownloadUrl = null
                    if (artworkUrl == url && bitmap != null) {
                        lastFailedArtworkUrl = null
                        currentArtworkBitmap = bitmap
                        instance?.refreshMediaNotification()
                    } else if (bitmap == null) {
                        lastFailedArtworkUrl = url
                    }
                }
            }.start()
        }

        private fun downloadBitmap(url: String): Bitmap? {
            if (url.startsWith("data:")) {
                val base64 = url.substringAfter("base64,", "")
                if (base64.isBlank()) return null
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            val connection = URL(url).openConnection() as HttpURLConnection
            return try {
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
                )
                val code = connection.responseCode
                if (code !in 200..299) {
                    Log.w(SERVICE_TAG, "artwork http $code for $url")
                    null
                } else {
                    BitmapFactory.decodeStream(connection.inputStream)
                }
            } finally {
                connection.disconnect()
            }
        }

        private fun scaleDown(bitmap: Bitmap, maxSize: Int): Bitmap {
            val largest = maxOf(bitmap.width, bitmap.height)
            if (largest <= maxSize) return bitmap
            val scale = maxSize.toFloat() / largest
            return Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }

        fun stopNow(context: Context) {
            mainHandler.post {
                cancelPendingStop()
                val appContext = context.applicationContext
                appContext.stopService(Intent(appContext, PlaybackService::class.java))
            }
        }

        private fun scheduleStop(context: Context) {
            val appContext = context.applicationContext
            pendingStop = Runnable {
                appContext.stopService(Intent(appContext, PlaybackService::class.java))
            }.also { mainHandler.postDelayed(it, STOP_DEBOUNCE_MS) }
        }

        private fun cancelPendingStop() {
            pendingStop?.let { mainHandler.removeCallbacks(it) }
            pendingStop = null
        }
    }
}
