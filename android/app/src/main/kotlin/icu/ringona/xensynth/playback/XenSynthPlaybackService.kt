package icu.ringona.xensynth.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import icu.ringona.xensynth.R
import icu.ringona.xensynth.platform.NativePlaybackSnapshot
import icu.ringona.xensynth.platform.XenSynthPlaybackCoordinator

/** Foreground media service that keeps score playback alive outside the Activity. */
class XenSynthPlaybackService : MediaBrowserServiceCompat() {
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var resumeOnAudioFocusGain = false
    private var foreground = false
    private var lastPlaying = false

    private val positionUpdater = object : Runnable {
        override fun run() {
            val snapshot = XenSynthPlaybackCoordinator.snapshot()
            publishPlaybackState(snapshot)
            if (lastPlaying != snapshot.playing) updateNotification(snapshot)
            lastPlaying = snapshot.playing
            handler.postDelayed(this, POSITION_UPDATE_MILLIS)
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pausePlayback()
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnAudioFocusGain = false
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnAudioFocusGain = XenSynthPlaybackCoordinator.snapshot().playing
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (resumeOnAudioFocusGain) {
                    resumeOnAudioFocusGain = false
                    resumePlayback(requestFocus = false)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        registerNoisyReceiver()

        mediaSession = MediaSessionCompat(this, MEDIA_SESSION_TAG).apply {
            setCallback(MediaSessionCallback())
            setSessionActivity(contentIntent())
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
        refreshMediaState()
        handler.post(positionUpdater)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            MediaButtonReceiver.handleIntent(mediaSession, intent)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            stopPlayback()
            return START_NOT_STICKY
        }
        refreshMediaState()
        return START_NOT_STICKY
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?,
    ): BrowserRoot = BrowserRoot(MEDIA_ROOT_ID, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>,
    ) {
        result.sendResult(emptyList())
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopPlayback()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (runningInstance === this) runningInstance = null
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(noisyReceiver) }
        abandonAudioFocus()
        XenSynthPlaybackCoordinator.dispose()
        mediaSession.run {
            isActive = false
            release()
        }
        @Suppress("DEPRECATION")
        stopForeground(true)
        notificationManager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun resumePlayback(requestFocus: Boolean = true) {
        if (requestFocus && !requestAudioFocus()) return
        XenSynthPlaybackCoordinator.resume()
        refreshMediaState()
    }

    private fun pausePlayback() {
        XenSynthPlaybackCoordinator.pause()
        abandonAudioFocus()
        refreshMediaState()
    }

    private fun stopPlayback() {
        XenSynthPlaybackCoordinator.stop()
        abandonAudioFocus()
        refreshMediaState(removeNotification = true)
        stopSelf()
    }

    private fun refreshMediaState(removeNotification: Boolean = false) {
        val snapshot = XenSynthPlaybackCoordinator.snapshot()
        publishMetadata(snapshot)
        publishPlaybackState(snapshot)
        if (removeNotification) {
            @Suppress("DEPRECATION")
            stopForeground(true)
            foreground = false
            notificationManager.cancel(NOTIFICATION_ID)
        } else {
            updateNotification(snapshot)
        }
        lastPlaying = snapshot.playing
    }

    private fun publishMetadata(snapshot: NativePlaybackSnapshot) {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, snapshot.title)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, snapshot.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.app_name))
                .putLong(
                    MediaMetadataCompat.METADATA_KEY_DURATION,
                    (snapshot.durationSeconds * MILLIS_PER_SECOND).toLong().coerceAtLeast(0L),
                )
                .build(),
        )
    }

    private fun publishPlaybackState(snapshot: NativePlaybackSnapshot) {
        val state = when {
            snapshot.playing -> PlaybackStateCompat.STATE_PLAYING
            snapshot.positionSeconds > 0.0 -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO,
                )
                .setState(
                    state,
                    (snapshot.positionSeconds * MILLIS_PER_SECOND).toLong().coerceAtLeast(0L),
                    if (snapshot.playing) 1f else 0f,
                    System.currentTimeMillis(),
                )
                .build(),
        )
    }

    private fun updateNotification(snapshot: NativePlaybackSnapshot) {
        if (snapshot.playing && !requestAudioFocus()) {
            XenSynthPlaybackCoordinator.pause()
            refreshMediaState()
            return
        }
        val notification = buildNotification(snapshot)
        if (snapshot.playing) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foreground = true
        } else {
            if (foreground) {
                @Suppress("DEPRECATION")
                stopForeground(false)
                foreground = false
            }
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(snapshot: NativePlaybackSnapshot): Notification {
        val playPauseAction = if (snapshot.playing) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                getString(R.string.pause),
                mediaButtonIntent(PlaybackStateCompat.ACTION_PAUSE),
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                getString(R.string.play),
                mediaButtonIntent(PlaybackStateCompat.ACTION_PLAY),
            )
        }
        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.stop),
            mediaButtonIntent(PlaybackStateCompat.ACTION_STOP),
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(snapshot.title)
            .setContentText(getString(if (snapshot.playing) R.string.playing else R.string.paused))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent())
            .setDeleteIntent(mediaButtonIntent(PlaybackStateCompat.ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(snapshot.playing)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .build()
    }

    private fun mediaButtonIntent(action: Long): PendingIntent? =
        MediaButtonReceiver.buildMediaButtonPendingIntent(this, action)

    private fun contentIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.playback_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.playback_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun registerNoisyReceiver() {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(noisyReceiver, filter)
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(audioFocusChangeListener, handler)
            .build()
        audioFocusRequest = request
        hasAudioFocus = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        audioFocusRequest = null
        hasAudioFocus = false
    }

    private inner class MediaSessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() = resumePlayback()

        override fun onPause() = pausePlayback()

        override fun onStop() = stopPlayback()

        override fun onSeekTo(pos: Long) {
            XenSynthPlaybackCoordinator.seek(pos / MILLIS_PER_SECOND)
            refreshMediaState()
        }
    }

    companion object {
        private const val MEDIA_ROOT_ID = "xensynth"
        private const val MEDIA_SESSION_TAG = "XenSynthPlayback"
        private const val NOTIFICATION_CHANNEL_ID = "xensynth_playback"
        private const val NOTIFICATION_ID = 0x5846
        private const val ACTION_REFRESH = "icu.ringona.xensynth.playback.REFRESH"
        private const val ACTION_STOP = "icu.ringona.xensynth.playback.STOP"
        private const val POSITION_UPDATE_MILLIS = 500L
        private const val MILLIS_PER_SECOND = 1_000.0

        @Volatile
        private var runningInstance: XenSynthPlaybackService? = null

        fun startOrRefresh(context: Context) {
            val intent = Intent(context, XenSynthPlaybackService::class.java)
                .setAction(ACTION_REFRESH)
            ContextCompat.startForegroundService(context, intent)
        }

        fun refreshIfRunning() {
            runningInstance?.refreshMediaState()
        }

        fun stopIfRunning(context: Context) {
            runningInstance?.stopPlayback()
                ?: context.stopService(Intent(context, XenSynthPlaybackService::class.java))
        }

        fun isRunning(): Boolean = runningInstance != null
    }
}
