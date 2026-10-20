package com.karthiban.powermotivation.lockscreen

import android.annotation.SuppressLint
import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.karthiban.powermotivation.PowerMotivationApp
import com.karthiban.powermotivation.R
import com.karthiban.powermotivation.model.*
import com.karthiban.powermotivation.ui.WidgetPlayerActivity
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * Core Engine that delivers:
 * 1. Direct Autoplay Home Screen Widget (WindowManager hardware surface that plays automatically
 *    when unlocked on the Home Screen, and supports direct Left/Right swipes without opening any app).
 * 2. Automatic Lock Screen Widget (Automatically displays and plays on Lock Screen as soon as screen turns on).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LockScreenKeyguardService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var floatingRoot: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var playerView: PlayerView? = null
    private var photoView: ImageView? = null
    private var volumeBtn: ImageView? = null
    private var emptyLabel: TextView? = null

    private var exoPlayer: ExoPlayer? = null
    private var activeContext: DisplayContext = DisplayContext.HOME_SCREEN
    private var settings: ContextSettings = ContextSettings()
    private var playlist: List<MediaItemEntity> = emptyList()
    private var currentIndex: Int = 0
    private var isScreenInteractive: Boolean = true
    private var launcherPackages: Set<String> = emptySet()
    private var monitorJob: Job? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenInteractive = false
                    pausePlayback()
                    // Pre-launch Lock Screen Widget so it's immediately visible when screen turns on
                    serviceScope.launch {
                        val lockSettings = PowerMotivationApp.instance.settingsRepository
                            .getSettingsSync(DisplayContext.LOCK_SCREEN)
                        if (lockSettings.isEnabled) {
                            launchLockScreenActivity()
                        }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenInteractive = true
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (km.isKeyguardLocked) {
                        serviceScope.launch {
                            val lockSettings = PowerMotivationApp.instance.settingsRepository
                                .getSettingsSync(DisplayContext.LOCK_SCREEN)
                            if (lockSettings.isEnabled) {
                                launchLockScreenActivity()
                            }
                        }
                    } else {
                        switchContextAndResume(DisplayContext.HOME_SCREEN)
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    // User just unlocked the POCO M2 -> Immediately autoplay Home Screen Widget!
                    isScreenInteractive = true
                    switchContextAndResume(DisplayContext.HOME_SCREEN)
                }
                ACTION_RELOAD_ENGINE -> {
                    switchContextAndResume(activeContext, forceReload = true)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startMinimalNotification()
        resolveLauncherPackages()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ACTION_RELOAD_ENGINE)
        }
        registerReceiver(screenStateReceiver, filter)

        if (Settings.canDrawOverlays(this)) {
            createFloatingWidgetWindow()
            switchContextAndResume(DisplayContext.HOME_SCREEN, forceReload = true)
        }
        startForegroundAppMonitor()
    }

    private fun launchLockScreenActivity() {
        val lockIntent = Intent(this, WidgetPlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
            putExtra(WidgetPlayerActivity.EXTRA_CONTEXT_MODE, DisplayContext.LOCK_SCREEN.name)
        }
        try {
            startActivity(lockIntent)
        } catch (_: Exception) {}
    }

    private fun resolveLauncherPackages() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolves = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val pkgs = resolves.map { it.activityInfo.packageName }.toMutableSet()
        pkgs.add("com.mi.android.globallauncher") // POCO Launcher
        pkgs.add("com.miui.home") // MIUI Home
        launcherPackages = pkgs
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingWidgetWindow() {
        if (floatingRoot != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density

        val container = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 18f * density
            }
            clipToOutline = true
        }

        val pView = PlayerView(this).apply {
            useController = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        val iView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        val eText = TextView(this).apply {
            text = "Add photos/videos in Power Motivation app"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        val volBtn = ImageView(this).apply {
            setBackgroundResource(R.drawable.bg_volume_pill)
            setImageResource(R.drawable.ic_volume_off)
            val sizePx = (32 * density).toInt()
            val marginPx = (10 * density).toInt()
            val padPx = (6 * density).toInt()
            setPadding(padPx, padPx, padPx, padPx)
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(marginPx, marginPx, marginPx, marginPx)
            }
            setOnClickListener {
                serviceScope.launch {
                    val newSound = PowerMotivationApp.instance.settingsRepository.toggleSound(activeContext)
                    settings = settings.copy(isSoundEnabled = newSound)
                    exoPlayer?.volume = if (newSound) settings.preferredVolume else 0f
                    setImageResource(if (newSound) R.drawable.ic_volume_on else R.drawable.ic_volume_off)
                }
            }
        }

        val touchOverlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDraggingWidget = false

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onLongPress(e: MotionEvent) {
                // Long press allows dragging the widget anywhere on the Home Screen
                isDraggingWidget = true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null || isDraggingWidget) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (abs(diffX) > abs(diffY) && abs(diffX) > 60 && abs(velocityX) > 60) {
                    if (diffX < 0) {
                        navigateRelative(1) // Swipe Left -> Next video/photo
                    } else {
                        navigateRelative(-1) // Swipe Right -> Previous video/photo
                    }
                    return true
                }
                return false
            }
        })

        touchOverlay.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams?.x ?: 0
                    initialY = layoutParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDraggingWidget = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingWidget && layoutParams != null && floatingRoot != null) {
                        layoutParams!!.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams!!.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager?.updateViewLayout(floatingRoot, layoutParams)
                        } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingWidget = false
                }
            }
            true
        }

        container.addView(pView)
        container.addView(iView)
        container.addView(eText)
        container.addView(touchOverlay)
        container.addView(volBtn)

        val (wPx, hPx) = computeWidgetSizePx("LARGE")
        val params = WindowManager.LayoutParams(
            wPx,
            hPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (110 * density).toInt()
        }

        floatingRoot = container
        playerView = pView
        photoView = iView
        volumeBtn = volBtn
        emptyLabel = eText
        layoutParams = params

        try {
            windowManager?.addView(container, params)
        } catch (_: Exception) {}
    }

    private fun computeWidgetSizePx(preset: String): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        val density = metrics.density
        return if (preset == "SMALL") {
            Pair((190 * density).toInt(), (190 * density).toInt())
        } else {
            Pair((metrics.widthPixels * 0.90f).toInt(), (240 * density).toInt())
        }
    }

    private fun updateWidgetSizeIfNeeded(preset: String) {
        val root = floatingRoot ?: return
        val params = layoutParams ?: return
        val (newW, newH) = computeWidgetSizePx(preset)
        if (params.width != newW || params.height != newH) {
            params.width = newW
            params.height = newH
            try {
                windowManager?.updateViewLayout(root, params)
            } catch (_: Exception) {}
        }
    }

    private fun switchContextAndResume(targetContext: DisplayContext, forceReload: Boolean = false) {
        serviceScope.launch {
            if (!Settings.canDrawOverlays(this@LockScreenKeyguardService)) return@launch
            if (floatingRoot == null) createFloatingWidgetWindow()

            val app = PowerMotivationApp.instance
            activeContext = targetContext
            settings = app.settingsRepository.getSettingsSync(targetContext)

            if (!settings.isEnabled) {
                floatingRoot?.visibility = View.GONE
                pausePlayback()
                return@launch
            }

            if (targetContext == DisplayContext.HOME_SCREEN) {
                updateWidgetSizeIfNeeded(settings.widgetSizePreset)
            }

            val newList = app.mediaRepository.getFilteredPlaylist(targetContext)
            if (forceReload || newList != playlist) {
                playlist = newList
            }

            floatingRoot?.visibility = View.VISIBLE
            if (playlist.isEmpty()) {
                emptyLabel?.visibility = View.VISIBLE
                playerView?.visibility = View.GONE
                photoView?.visibility = View.GONE
                volumeBtn?.visibility = View.GONE
                pausePlayback()
                return@launch
            }

            emptyLabel?.visibility = View.GONE
            currentIndex = settings.currentIndex.coerceIn(playlist.indices)
            renderActiveItem()
        }
    }

    private fun navigateRelative(delta: Int) {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + delta).mod(playlist.size)
        serviceScope.launch {
            PowerMotivationApp.instance.settingsRepository.updateIndex(activeContext, currentIndex)
            renderActiveItem()
        }
    }

    private fun renderActiveItem() {
        if (playlist.isEmpty() || currentIndex !in playlist.indices) return
        val item = playlist[currentIndex]

        if (settings.scaleMode == ScaleMode.CROP) {
            photoView?.scaleType = ImageView.ScaleType.CENTER_CROP
            playerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            photoView?.scaleType = ImageView.ScaleType.FIT_CENTER
            playerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        if (item.mediaType == MediaType.PHOTO) {
            exoPlayer?.stop()
            playerView?.visibility = View.GONE
            photoView?.visibility = View.VISIBLE
            volumeBtn?.visibility = View.GONE

            val bmp = PowerMotivationApp.instance.mediaRepository.loadScaledBitmap(item, 1080, 1080)
            if (bmp != null) {
                photoView?.setImageBitmap(bmp)
            } else {
                navigateRelative(1)
            }
        } else {
            photoView?.visibility = View.GONE
            playerView?.visibility = View.VISIBLE
            volumeBtn?.visibility = View.VISIBLE
            volumeBtn?.setImageResource(
                if (settings.isSoundEnabled) R.drawable.ic_volume_on else R.drawable.ic_volume_off
            )

            val player = getOrCreatePlayer()
            player.setMediaItem(MediaItem.fromUri(Uri.parse(item.uriString)))
            player.repeatMode = if (settings.loopVideo) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            player.volume = if (settings.isSoundEnabled) settings.preferredVolume else 0f
            player.prepare()
            player.playWhenReady = settings.autoPlayVideo && isScreenInteractive
        }
    }

    private fun getOrCreatePlayer(): ExoPlayer {
        exoPlayer?.let { return it }
        return ExoPlayer.Builder(this).build().also { player ->
            playerView?.player = player
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED && !settings.loopVideo) {
                        navigateRelative(1)
                    }
                }
            })
            exoPlayer = player
        }
    }

    private fun pausePlayback() {
        exoPlayer?.pause()
    }

    /**
     * Automatically hides the Home Screen widget when the user opens another app
     * (when Usage Stats permission is granted), and auto-resumes as soon as they return to Home Screen.
     */
    private fun startForegroundAppMonitor() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                delay(900)
                if (!isScreenInteractive || floatingRoot == null) continue

                val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (km.isKeyguardLocked) {
                    // While keyguard is locked, LockScreen Widget Activity handles playback
                    if (floatingRoot?.visibility == View.VISIBLE) {
                        floatingRoot?.visibility = View.GONE
                        pausePlayback()
                    }
                    continue
                }

                val currentPkg = getTopPackageName()
                if (currentPkg != null) {
                    val isOnHomeLauncher = launcherPackages.contains(currentPkg)
                    if (isOnHomeLauncher && settings.isEnabled) {
                        if (floatingRoot?.visibility != View.VISIBLE) {
                            floatingRoot?.visibility = View.VISIBLE
                            if (playlist.getOrNull(currentIndex)?.mediaType == MediaType.VIDEO &&
                                settings.autoPlayVideo
                            ) {
                                exoPlayer?.play()
                            }
                        }
                    } else if (!isOnHomeLauncher && currentPkg != packageName) {
                        if (floatingRoot?.visibility == View.VISIBLE) {
                            floatingRoot?.visibility = View.GONE
                            pausePlayback()
                        }
                    }
                }
            }
        }
    }

    private fun getTopPackageName(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 10_000
            val events = usm.queryEvents(start, end)
            val event = UsageEvents.Event()
            var lastPkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastPkg = event.packageName
                }
            }
            lastPkg
        } catch (_: Exception) {
            null
        }
    }

    private fun startMinimalNotification() {
        val channelId = "power_motivation_engine"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Power Motivation Autoplay Widget Engine",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Power Motivation Active")
            .setContentText("Autoplay Home & Lock Screen media widget running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(1001, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Settings.canDrawOverlays(this)) {
            createFloatingWidgetWindow()
            switchContextAndResume(DisplayContext.HOME_SCREEN, forceReload = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        unregisterReceiver(screenStateReceiver)
        exoPlayer?.release()
        exoPlayer = null
        floatingRoot?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        floatingRoot = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_RELOAD_ENGINE = "com.karthiban.powermotivation.ACTION_RELOAD_ENGINE"

        fun notifyEngineReload(context: Context) {
            context.sendBroadcast(Intent(ACTION_RELOAD_ENGINE))
        }
    }
}