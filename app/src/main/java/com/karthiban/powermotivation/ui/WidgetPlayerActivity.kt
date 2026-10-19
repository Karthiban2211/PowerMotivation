package com.karthiban.powermotivation.ui

import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.karthiban.powermotivation.PowerMotivationApp
import com.karthiban.powermotivation.R
import com.karthiban.powermotivation.databinding.ActivityWidgetPlayerBinding
import com.karthiban.powermotivation.model.ContextSettings
import com.karthiban.powermotivation.model.DisplayContext
import com.karthiban.powermotivation.model.MediaItemEntity
import com.karthiban.powermotivation.model.MediaType
import com.karthiban.powermotivation.model.ScaleMode
import com.karthiban.powermotivation.widget.BaseMotivationWidgetProvider
import kotlinx.coroutines.launch
import kotlin.math.abs

@UnstableApi
class WidgetPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWidgetPlayerBinding
    private var exoPlayer: ExoPlayer? = null
    private lateinit var gestureDetector: GestureDetector

    private var displayContext: DisplayContext = DisplayContext.HOME_SCREEN
    private var settings: ContextSettings = ContextSettings()
    private var playlist: List<MediaItemEntity> = emptyList()
    private var currentIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val modeStr = intent.getStringExtra(EXTRA_CONTEXT_MODE) ?: DisplayContext.HOME_SCREEN.name
        displayContext = DisplayContext.valueOf(modeStr)

        if (displayContext == DisplayContext.LOCK_SCREEN) {
            configureLockScreenWindow()
        }

        binding = ActivityWidgetPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (displayContext == DisplayContext.HOME_SCREEN) {
            val preset = intent.getStringExtra(EXTRA_SIZE_PRESET) ?: "LARGE"
            configureFloatingWidgetDimensions(preset)
        } else {
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }

        setupSwipeGestureDetector()
        setupVolumeControl()
        loadContextAndPlay()
    }

    private fun configureLockScreenWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun configureFloatingWidgetDimensions(preset: String) {
        val metrics = resources.displayMetrics
        val density = metrics.density
        val widthPx: Int
        val heightPx: Int

        if (preset == "SMALL") {
            widthPx = (200 * density).toInt()
            heightPx = (200 * density).toInt()
        } else {
            widthPx = (metrics.widthPixels * 0.90f).toInt()
            heightPx = (280 * density).toInt()
        }

        window.attributes = window.attributes.apply {
            width = widthPx
            height = heightPx
            gravity = Gravity.CENTER
            dimAmount = 0.15f
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    private fun setupSwipeGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 80
            private val SWIPE_VELOCITY_THRESHOLD = 80

            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (abs(diffX) > abs(diffY) &&
                    abs(diffX) > SWIPE_THRESHOLD &&
                    abs(velocityX) > SWIPE_VELOCITY_THRESHOLD
                ) {
                    if (diffX < 0) {
                        navigateRelative(1)
                    } else {
                        navigateRelative(-1)
                    }
                    return true
                } else if (displayContext == DisplayContext.LOCK_SCREEN &&
                    diffY < -SWIPE_THRESHOLD * 1.5 &&
                    abs(velocityY) > SWIPE_VELOCITY_THRESHOLD
                ) {
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        km.requestDismissKeyguard(this@WidgetPlayerActivity, null)
                    }
                    finish()
                    return true
                }
                return false
            }
        })

        binding.touchInterceptorSurface.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun setupVolumeControl() {
        binding.btnPersistentVolume.setOnClickListener {
            lifecycleScope.launch {
                val newSoundState = PowerMotivationApp.instance.settingsRepository.toggleSound(displayContext)
                settings = settings.copy(isSoundEnabled = newSoundState)
                applyAudioSettingsToPlayer()
                updateVolumeIconVisibility(playlist.getOrNull(currentIndex))
                if (displayContext == DisplayContext.HOME_SCREEN) {
                    BaseMotivationWidgetProvider.notifyAllWidgetsChanged(this@WidgetPlayerActivity)
                }
            }
        }
    }

    private fun loadContextAndPlay() {
        lifecycleScope.launch {
            val app = PowerMotivationApp.instance
            settings = app.settingsRepository.getSettingsSync(displayContext)
            if (!settings.isEnabled) {
                finish()
                return@launch
            }
            playlist = app.mediaRepository.getFilteredPlaylist(displayContext)
            if (playlist.isEmpty()) {
                binding.emptyStateText.visibility = View.VISIBLE
                binding.btnPersistentVolume.visibility = View.GONE
                return@launch
            }

            binding.emptyStateText.visibility = View.GONE
            val requestedIndex = intent.getIntExtra(EXTRA_START_INDEX, settings.currentIndex)
            currentIndex = requestedIndex.coerceIn(playlist.indices)
            renderCurrentMedia()
        }
    }

    private fun navigateRelative(delta: Int) {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + delta).mod(playlist.size)
        lifecycleScope.launch {
            PowerMotivationApp.instance.settingsRepository.updateIndex(displayContext, currentIndex)
        }
        renderCurrentMedia()
    }

    private fun renderCurrentMedia() {
        if (playlist.isEmpty() || currentIndex !in playlist.indices) return
        val item = playlist[currentIndex]

        updateVolumeIconVisibility(item)
        applyScaleMode(settings.scaleMode)

        if (item.mediaType == MediaType.PHOTO) {
            exoPlayer?.stop()
            binding.exoPlayerView.visibility = View.GONE
            binding.photoImageView.visibility = View.VISIBLE

            val bmp = PowerMotivationApp.instance.mediaRepository.loadScaledBitmap(item, 1440, 1440)
            if (bmp != null) {
                binding.photoImageView.setImageBitmap(bmp)
            } else {
                navigateRelative(1)
            }
        } else {
            binding.photoImageView.visibility = View.GONE
            binding.exoPlayerView.visibility = View.VISIBLE

            val player = getOrCreatePlayer()
            val mediaItem = MediaItem.fromUri(Uri.parse(item.uriString))
            player.setMediaItem(mediaItem)
            player.repeatMode = if (settings.loopVideo) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            applyAudioSettingsToPlayer()
            player.prepare()
            player.playWhenReady = settings.autoPlayVideo
        }
    }

    private fun getOrCreatePlayer(): ExoPlayer {
        exoPlayer?.let { return it }
        return ExoPlayer.Builder(this).build().also { player ->
            binding.exoPlayerView.player = player
            binding.exoPlayerView.useController = false
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

    private fun applyScaleMode(scaleMode: ScaleMode) {
        if (scaleMode == ScaleMode.CROP) {
            binding.photoImageView.scaleType = ImageView.ScaleType.CENTER_CROP
            binding.exoPlayerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            binding.photoImageView.scaleType = ImageView.ScaleType.FIT_CENTER
            binding.exoPlayerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun applyAudioSettingsToPlayer() {
        exoPlayer?.volume = if (settings.isSoundEnabled) settings.preferredVolume else 0f
    }

    private fun updateVolumeIconVisibility(item: MediaItemEntity?) {
        if (item == null || item.mediaType == MediaType.PHOTO) {
            binding.btnPersistentVolume.visibility = View.GONE
        } else {
            binding.btnPersistentVolume.visibility = View.VISIBLE
            binding.btnPersistentVolume.setImageResource(
                if (settings.isSoundEnabled) R.drawable.ic_volume_on else R.drawable.ic_volume_off
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (playlist.isNotEmpty()) {
            renderCurrentMedia()
        }
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CONTEXT_MODE = "extra_context_mode"
        const val EXTRA_START_INDEX = "extra_start_index"
        const val EXTRA_SIZE_PRESET = "extra_size_preset"
    }
}