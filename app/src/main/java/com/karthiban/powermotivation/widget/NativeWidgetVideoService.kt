package com.karthiban.powermotivation.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.karthiban.powermotivation.PowerMotivationApp
import com.karthiban.powermotivation.R
import com.karthiban.powermotivation.model.ContextSettings
import com.karthiban.powermotivation.model.DisplayContext
import com.karthiban.powermotivation.model.MediaItemEntity
import com.karthiban.powermotivation.model.MediaType
import com.karthiban.powermotivation.ui.MainActivity
import kotlinx.coroutines.*

/**
 * Renders photos and decodes live video frames directly into the standard Home Screen
 * AppWidgetProvider (RemoteViews) without ANY overlay window or popup activity!
 *
 * Also pauses immediately when the screen turns off (ACTION_SCREEN_OFF) and resumes
 * when unlocked to the Home Screen (ACTION_USER_PRESENT / ACTION_SCREEN_ON).
 */
class NativeWidgetVideoService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var videoFrameJob: Job? = null
    private var audioPlayer: ExoPlayer? = null

    private var settings: ContextSettings = ContextSettings()
    private var playlist: List<MediaItemEntity> = emptyList()
    private var currentIndex: Int = 0
    private var isScreenInteractive: Boolean = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    stopVideoLoopAndAudio()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    isScreenInteractive = true
                    reloadAndRenderWidget()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startMinimalForegroundNotification()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenInteractive = pm.isInteractive

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        reloadAndRenderWidget()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STEP -> {
                val delta = intent.getIntExtra(EXTRA_DELTA, 1)
                performStep(delta)
            }
            ACTION_MUTE -> {
                performMuteToggle()
            }
            else -> {
                reloadAndRenderWidget()
            }
        }
        return START_STICKY
    }

    private fun performStep(delta: Int) {
        serviceScope.launch {
            val app = PowerMotivationApp.instance
            settings = app.settingsRepository.getSettingsSync(DisplayContext.HOME_SCREEN)
            playlist = app.mediaRepository.getFilteredPlaylist(DisplayContext.HOME_SCREEN)
            if (playlist.isEmpty()) return@launch
            currentIndex = (settings.currentIndex + delta).mod(playlist.size)
            app.settingsRepository.updateIndex(DisplayContext.HOME_SCREEN, currentIndex)
            reloadAndRenderWidget()
        }
    }

    private fun performMuteToggle() {
        serviceScope.launch {
            val newSound = PowerMotivationApp.instance.settingsRepository.toggleSound(DisplayContext.HOME_SCREEN)
            settings = settings.copy(isSoundEnabled = newSound)
            audioPlayer?.volume = if (newSound) settings.preferredVolume else 0f
            updateAllWidgetViewsStatic(null)
        }
    }

    private fun reloadAndRenderWidget() {
        serviceScope.launch {
            val app = PowerMotivationApp.instance
            settings = app.settingsRepository.getSettingsSync(DisplayContext.HOME_SCREEN)
            playlist = app.mediaRepository.getFilteredPlaylist(DisplayContext.HOME_SCREEN)

            if (!hasAnyActiveWidgets()) {
                stopVideoLoopAndAudio()
                return@launch
            }

            if (!settings.isEnabled || playlist.isEmpty()) {
                stopVideoLoopAndAudio()
                pushEmptyOrDisabledState()
                return@launch
            }

            currentIndex = settings.currentIndex.coerceIn(playlist.indices)
            val activeItem = playlist[currentIndex]

            if (activeItem.mediaType == MediaType.PHOTO) {
                stopVideoLoopAndAudio()
                val rawBmp = withContext(Dispatchers.IO) {
                    app.mediaRepository.loadScaledBitmap(activeItem, 600, 600)
                }
                if (rawBmp != null) {
                    val formatted = withContext(Dispatchers.IO) {
                        app.mediaRepository.formatBitmapForWidget(rawBmp, 520, 380, settings.scaleMode)
                    }
                    updateAllWidgetViewsStatic(formatted, isPhoto = true)
                }
            } else {
                startInWidgetVideoPlayback(activeItem)
            }
        }
    }

    private fun startInWidgetVideoPlayback(item: MediaItemEntity) {
        stopVideoLoopAndAudio()
        if (!isScreenInteractive) return

        // Start synchronized ExoPlayer audio if video autoplay is on
        if (settings.autoPlayVideo) {
            val player = getOrCreateAudioPlayer()
            player.setMediaItem(MediaItem.fromUri(Uri.parse(item.uriString)))
            player.repeatMode = if (settings.loopVideo) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            player.volume = if (settings.isSoundEnabled) settings.preferredVolume else 0f
            player.prepare()
            player.playWhenReady = true
        }

        // Decode frames and push directly into the normal Home Screen Widget RemoteViews
        videoFrameJob = serviceScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this@NativeWidgetVideoService, Uri.parse(item.uriString))
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 5000L

                var currentTimeMs = 0L
                val stepMs = 180L // ~5.5 fps smooth in-widget frame stream (safe for Binder IPC & battery)

                while (isActive && isScreenInteractive) {
                    val rawFrame = retriever.getFrameAtTime(
                        currentTimeMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    ) ?: retriever.getFrameAtTime(0)

                    if (rawFrame != null) {
                        val formatted = PowerMotivationApp.instance.mediaRepository
                            .formatBitmapForWidget(rawFrame, 440, 300, settings.scaleMode)
                        withContext(Dispatchers.Main) {
                            updateAllWidgetViewsStatic(formatted, isPhoto = false)
                        }
                    }

                    if (!settings.autoPlayVideo) break

                    delay(stepMs)
                    currentTimeMs += stepMs
                    if (currentTimeMs >= durationMs) {
                        if (settings.loopVideo) {
                            currentTimeMs = 0L
                        } else {
                            withContext(Dispatchers.Main) {
                                performStep(1)
                            }
                            break
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {}
            }
        }
    }

    private var lastRenderedBitmap: android.graphics.Bitmap? = null
    private var lastWasPhoto: Boolean = false

    private fun updateAllWidgetViewsStatic(
        bitmap: android.graphics.Bitmap?,
        isPhoto: Boolean = lastWasPhoto
    ) {
        if (bitmap != null) {
            lastRenderedBitmap = bitmap
            lastWasPhoto = isPhoto
        }
        val bmpToDraw = lastRenderedBitmap ?: return

        val manager = AppWidgetManager.getInstance(this)
        val views = buildNormalWidgetRemoteViews(bmpToDraw, isPhoto)

        val smallIds = manager.getAppWidgetIds(ComponentName(this, SmallMotivationWidgetProvider::class.java))
        for (id in smallIds) {
            manager.updateAppWidget(id, views)
        }
        val largeIds = manager.getAppWidgetIds(ComponentName(this, LargeMotivationWidgetProvider::class.java))
        for (id in largeIds) {
            manager.updateAppWidget(id, views)
        }
    }

    private fun buildNormalWidgetRemoteViews(
        bitmap: android.graphics.Bitmap,
        isPhoto: Boolean
    ): RemoteViews {
        val views = RemoteViews(packageName, R.layout.widget_motivation_container)
        views.setViewVisibility(R.id.widget_empty_view, View.GONE)
        views.setViewVisibility(R.id.widget_media_image, View.VISIBLE)
        views.setImageViewBitmap(R.id.widget_media_image, bitmap)

        // Left & Right touch zones inside the normal widget to switch items without opening any app
        val prevIntent = Intent(this, LargeMotivationWidgetProvider::class.java).apply {
            action = BaseMotivationWidgetProvider.ACTION_WIDGET_PREV
        }
        val prevPending = PendingIntent.getBroadcast(
            this,
            101,
            prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_touch_prev, prevPending)

        val nextIntent = Intent(this, LargeMotivationWidgetProvider::class.java).apply {
            action = BaseMotivationWidgetProvider.ACTION_WIDGET_NEXT
        }
        val nextPending = PendingIntent.getBroadcast(
            this,
            102,
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_touch_next, nextPending)

        // Small Volume Icon (Only visible on videos; hidden on photos)
        if (isPhoto) {
            views.setViewVisibility(R.id.widget_volume_button, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_volume_button, View.VISIBLE)
            views.setImageViewResource(
                R.id.widget_volume_button,
                if (settings.isSoundEnabled) R.drawable.ic_volume_on else R.drawable.ic_volume_off
            )
            val muteIntent = Intent(this, LargeMotivationWidgetProvider::class.java).apply {
                action = BaseMotivationWidgetProvider.ACTION_WIDGET_MUTE_TOGGLE
            }
            val mutePending = PendingIntent.getBroadcast(
                this,
                103,
                muteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_volume_button, mutePending)
        }

        return views
    }

    private fun pushEmptyOrDisabledState() {
        val manager = AppWidgetManager.getInstance(this)
        val views = RemoteViews(packageName, R.layout.widget_motivation_container)
        views.setViewVisibility(R.id.widget_media_image, View.GONE)
        views.setViewVisibility(R.id.widget_volume_button, View.GONE)
        views.setViewVisibility(R.id.widget_empty_view, View.VISIBLE)
        views.setTextViewText(
            R.id.widget_empty_view,
            if (!settings.isEnabled) getString(R.string.home_widget_disabled)
            else getString(R.string.widget_empty_prompt)
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            109,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_empty_view, openPending)

        val smallIds = manager.getAppWidgetIds(ComponentName(this, SmallMotivationWidgetProvider::class.java))
        for (id in smallIds) manager.updateAppWidget(id, views)
        val largeIds = manager.getAppWidgetIds(ComponentName(this, LargeMotivationWidgetProvider::class.java))
        for (id in largeIds) manager.updateAppWidget(id, views)
    }

    private fun hasAnyActiveWidgets(): Boolean {
        val manager = AppWidgetManager.getInstance(this)
        val smallIds = manager.getAppWidgetIds(ComponentName(this, SmallMotivationWidgetProvider::class.java))
        val largeIds = manager.getAppWidgetIds(ComponentName(this, LargeMotivationWidgetProvider::class.java))
        return smallIds.isNotEmpty() || largeIds.isNotEmpty()
    }

    private fun getOrCreateAudioPlayer(): ExoPlayer {
        audioPlayer?.let { return it }
        return ExoPlayer.Builder(this).build().also { player ->
            player.setWakeMode(C.WAKE_MODE_NONE)
            audioPlayer = player
        }
    }

    private fun stopVideoLoopAndAudio() {
        videoFrameJob?.cancel()
        videoFrameJob = null
        audioPlayer?.pause()
    }

    private fun startMinimalForegroundNotification() {
        val channelId = "power_motivation_widget_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Home Screen Widget Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Home screen media widget active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(2001, notification)
    }

    override fun onDestroy() {
        stopVideoLoopAndAudio()
        audioPlayer?.release()
        audioPlayer = null
        unregisterReceiver(screenReceiver)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_STEP = "com.karthiban.powermotivation.ACTION_STEP"
        private const val ACTION_MUTE = "com.karthiban.powermotivation.ACTION_MUTE"
        private const val EXTRA_DELTA = "extra_delta"

        fun ensureRunningAndRender(context: Context) {
            val intent = Intent(context, NativeWidgetVideoService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stepMedia(context: Context, delta: Int) {
            val intent = Intent(context, NativeWidgetVideoService::class.java).apply {
                action = ACTION_STEP
                putExtra(EXTRA_DELTA, delta)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun toggleMute(context: Context) {
            val intent = Intent(context, NativeWidgetVideoService::class.java).apply {
                action = ACTION_MUTE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun notifyPlaylistOrSettingsChanged(context: Context) {
            ensureRunningAndRender(context)
        }
    }
}