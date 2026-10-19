package com.karthiban.powermotivation.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.karthiban.powermotivation.PowerMotivationApp
import com.karthiban.powermotivation.R
import com.karthiban.powermotivation.model.ContextSettings
import com.karthiban.powermotivation.model.DisplayContext
import com.karthiban.powermotivation.model.MediaItemEntity
import com.karthiban.powermotivation.model.ScaleMode
import kotlinx.coroutines.runBlocking

class MotivationWidgetRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return MotivationStackViewsFactory(applicationContext)
    }
}

class MotivationStackViewsFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var playlist: List<MediaItemEntity> = emptyList()
    private var settings: ContextSettings = ContextSettings()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        runBlocking {
            val app = PowerMotivationApp.instance
            settings = app.settingsRepository.getSettingsSync(DisplayContext.HOME_SCREEN)
            playlist = if (settings.isEnabled) {
                app.mediaRepository.getFilteredPlaylist(DisplayContext.HOME_SCREEN)
            } else {
                emptyList()
            }
        }
    }

    override fun onDestroy() {
        playlist = emptyList()
    }

    override fun getCount(): Int = playlist.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (position !in playlist.indices) return null
        val item = playlist[position]
        val views = RemoteViews(context.packageName, R.layout.widget_stack_item)

        val rawBitmap = PowerMotivationApp.instance.mediaRepository.loadScaledBitmap(item, 640, 640)
        if (rawBitmap != null) {
            val formatted = applyScaleModeToBitmap(rawBitmap, 600, 600, settings.scaleMode)
            views.setImageViewBitmap(R.id.widget_item_image, formatted)
        } else {
            views.setImageViewResource(R.id.widget_item_image, R.drawable.widget_preview_box)
        }

        val fillInIntent = Intent().apply {
            putExtra(BaseMotivationWidgetProvider.EXTRA_ITEM_INDEX, position)
        }
        views.setOnClickFillInIntent(R.id.widget_item_root, fillInIntent)

        return views
    }

    private fun applyScaleModeToBitmap(
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        scaleMode: ScaleMode
    ): Bitmap {
        val output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.RGB_565)
        val canvas = Canvas(output)
        val srcRatio = src.width.toFloat() / src.height
        val dstRatio = targetW.toFloat() / targetH

        if (scaleMode == ScaleMode.CROP) {
            val srcRect = if (srcRatio > dstRatio) {
                val newW = (src.height * dstRatio).toInt()
                val left = (src.width - newW) / 2
                Rect(left, 0, left + newW, src.height)
            } else {
                val newH = (src.width / dstRatio).toInt()
                val top = (src.height - newH) / 2
                Rect(0, top, src.width, top + newH)
            }
            canvas.drawBitmap(src, srcRect, Rect(0, 0, targetW, targetH), null)
        } else {
            val dstRect = if (srcRatio > dstRatio) {
                val drawH = (targetW / srcRatio).toInt()
                val top = (targetH - drawH) / 2
                Rect(0, top, targetW, top + drawH)
            } else {
                val drawW = (targetH * srcRatio).toInt()
                val left = (targetW - drawW) / 2
                Rect(left, 0, left + drawW, targetH)
            }
            canvas.drawBitmap(src, null, dstRect, null)
        }
        return output
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = playlist.getOrNull(position)?.id ?: position.toLong()
    override fun hasStableIds(): Boolean = true
}