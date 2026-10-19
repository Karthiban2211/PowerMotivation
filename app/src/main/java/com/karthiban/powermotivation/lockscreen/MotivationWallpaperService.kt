package com.karthiban.powermotivation.lockscreen

import android.app.KeyguardManager
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.karthiban.powermotivation.PowerMotivationApp
import com.karthiban.powermotivation.model.*
import kotlinx.coroutines.*
import kotlin.math.abs

class MotivationWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return MotivationWallpaperEngine()
    }

    inner class MotivationWallpaperEngine : Engine() {

        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var exoPlayer: ExoPlayer? = null
        private var currentContextMode: DisplayContext = DisplayContext.LOCK_SCREEN
        private var settings: ContextSettings = ContextSettings()
        private var playlist: List<MediaItemEntity> = emptyList()
        private var currentIndex: Int = 0
        private var surfaceWidth: Int = 1080
        private var surfaceHeight: Int = 2340

        private val volumeButtonRect = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val gestureDetector = GestureDetector(
            this@MotivationWallpaperService,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val activeItem = playlist.getOrNull(currentIndex)
                    if (activeItem?.mediaType == MediaType.VIDEO &&
                        volumeButtonRect.contains(e.x, e.y)
                    ) {
                        engineScope.launch {
                            val newSound = PowerMotivationApp.instance.settingsRepository.toggleSound(currentContextMode)
                            settings = settings.copy(isSoundEnabled = newSound)
                            exoPlayer?.volume = if (newSound) settings.preferredVolume else 0f
                        }
                        return true
                    }
                    return false
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    val diffX = e2.x - e1.x
                    val diffY = e2.y - e1.y
                    if (abs(diffX) > abs(diffY) && abs(diffX) > 90 && abs(velocityX) > 90) {
                        if (diffX < 0) {
                            stepPlaylist(1)
                        } else {
                            stepPlaylist(-1)
                        }
                        return true
                    }
                    return false
                }
            }
        )

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder?,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            val margin = 42f
            val size = 96f
            volumeButtonRect.set(
                width - size - margin,
                height - size - margin * 2.5f,
                width - margin,
                height - margin * 2.5f
            )
            if (isVisible) {
                refreshContextAndRender()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                currentContextMode = if (km.isKeyguardLocked) {
                    DisplayContext.LOCK_SCREEN
                } else {
                    DisplayContext.HOME_SCREEN
                }
                refreshContextAndRender()
            } else {
                exoPlayer?.pause()
                exoPlayer?.clearVideoSurface()
            }
        }

        override fun onTouchEvent(event: MotionEvent?) {
            if (event != null) {
                gestureDetector.onTouchEvent(event)
            }
            super.onTouchEvent(event)
        }

        private fun refreshContextAndRender() {
            engineScope.launch {
                val app = PowerMotivationApp.instance
                settings = app.settingsRepository.getSettingsSync(currentContextMode)
                if (!settings.isEnabled) {
                    exoPlayer?.stop()
                    drawBlankSurface("Experience disabled in settings")
                    return@launch
                }
                playlist = app.mediaRepository.getFilteredPlaylist(currentContextMode)
                if (playlist.isEmpty()) {
                    exoPlayer?.stop()
                    drawBlankSurface("Add motivational photos or videos in Power Motivation app")
                    return@launch
                }
                currentIndex = settings.currentIndex.coerceIn(playlist.indices)
                renderActiveItem()
            }
        }

        private fun stepPlaylist(delta: Int) {
            if (playlist.isEmpty()) return
            currentIndex = (currentIndex + delta).mod(playlist.size)
            engineScope.launch {
                PowerMotivationApp.instance.settingsRepository.updateIndex(currentContextMode, currentIndex)
                renderActiveItem()
            }
        }

        private fun renderActiveItem() {
            if (!isVisible || playlist.isEmpty()) return
            val item = playlist[currentIndex]

            if (item.mediaType == MediaType.PHOTO) {
                exoPlayer?.stop()
                exoPlayer?.clearVideoSurface()
                drawPhotoOnSurface(item)
            } else {
                val player = getOrCreatePlayer()
                player.setVideoSurfaceHolder(surfaceHolder)
                player.setMediaItem(MediaItem.fromUri(Uri.parse(item.uriString)))
                player.repeatMode = if (settings.loopVideo) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                player.volume = if (settings.isSoundEnabled) settings.preferredVolume else 0f
                player.prepare()
                player.playWhenReady = settings.autoPlayVideo
            }
        }

        private fun drawPhotoOnSurface(item: MediaItemEntity) {
            val bmp = PowerMotivationApp.instance.mediaRepository.loadScaledBitmap(
                item,
                surfaceWidth,
                surfaceHeight
            ) ?: return

            val holder = surfaceHolder ?: return
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(Color.BLACK)
                    val srcRatio = bmp.width.toFloat() / bmp.height
                    val dstRatio = surfaceWidth.toFloat() / surfaceHeight

                    if (settings.scaleMode == ScaleMode.CROP) {
                        val srcRect = if (srcRatio > dstRatio) {
                            val newW = (bmp.height * dstRatio).toInt()
                            val left = (bmp.width - newW) / 2
                            Rect(left, 0, left + newW, bmp.height)
                        } else {
                            val newH = (bmp.width / dstRatio).toInt()
                            val top = (bmp.height - newH) / 2
                            Rect(0, top, bmp.width, top + newH)
                        }
                        canvas.drawBitmap(bmp, srcRect, Rect(0, 0, surfaceWidth, surfaceHeight), paint)
                    } else {
                        val dstRect = if (srcRatio > dstRatio) {
                            val drawH = (surfaceWidth / srcRatio).toInt()
                            val top = (surfaceHeight - drawH) / 2
                            Rect(0, top, surfaceWidth, top + drawH)
                        } else {
                            val drawW = (surfaceHeight * srcRatio).toInt()
                            val left = (surfaceWidth - drawW) / 2
                            Rect(left, 0, left + drawW, surfaceHeight)
                        }
                        canvas.drawBitmap(bmp, null, dstRect, paint)
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) {}
                }
            }
        }

        private fun drawBlankSurface(message: String) {
            val holder = surfaceHolder ?: return
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(Color.BLACK)
                    paint.color = Color.WHITE
                    paint.textSize = 38f
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText(message, surfaceWidth / 2f, surfaceHeight / 2f, paint)
                }
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) {}
                }
            }
        }

        private fun getOrCreatePlayer(): ExoPlayer {
            exoPlayer?.let { return it }
            return ExoPlayer.Builder(this@MotivationWallpaperService).build().also {
                exoPlayer = it
            }
        }

        override fun onDestroy() {
            engineScope.cancel()
            exoPlayer?.release()
            exoPlayer = null
            super.onDestroy()
        }
    }
}