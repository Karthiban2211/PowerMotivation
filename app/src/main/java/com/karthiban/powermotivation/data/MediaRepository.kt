package com.karthiban.powermotivation.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.karthiban.powermotivation.model.DisplayContext
import com.karthiban.powermotivation.model.MediaItemEntity
import com.karthiban.powermotivation.model.MediaType
import com.karthiban.powermotivation.model.PlaylistTarget
import com.karthiban.powermotivation.model.ScaleMode
import com.karthiban.powermotivation.widget.NativeWidgetVideoService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MediaRepository(
    private val context: Context,
    private val mediaDao: MediaDao
) {
    val allMedia: Flow<List<MediaItemEntity>> = mediaDao.observeAllMedia()

    suspend fun getFilteredPlaylist(displayContext: DisplayContext): List<MediaItemEntity> =
        withContext(Dispatchers.IO) {
            val all = mediaDao.getAllMediaSync()
            val validItems = mutableListOf<MediaItemEntity>()

            for (item in all) {
                val matchesContext = when (displayContext) {
                    DisplayContext.HOME_SCREEN ->
                        item.playlistTarget == PlaylistTarget.BOTH || item.playlistTarget == PlaylistTarget.HOME_ONLY
                    DisplayContext.LOCK_SCREEN ->
                        item.playlistTarget == PlaylistTarget.BOTH || item.playlistTarget == PlaylistTarget.LOCK_ONLY
                }
                if (!matchesContext) continue

                if (isUriAccessible(Uri.parse(item.uriString))) {
                    validItems.add(item)
                }
            }
            validItems
        }

    fun isUriAccessible(uri: Uri): Boolean {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun importMediaUris(uris: List<Uri>, target: PlaylistTarget = PlaylistTarget.BOTH) =
        withContext(Dispatchers.IO) {
            var nextSort = mediaDao.getMaxSortOrder() + 1
            for (uri in uris) {
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (_: SecurityException) {}

                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val mediaType = if (mimeType.startsWith("video/")) MediaType.VIDEO else MediaType.PHOTO
                val name = resolveDisplayName(uri)

                val entity = MediaItemEntity(
                    uriString = uri.toString(),
                    displayName = name,
                    mimeType = mimeType,
                    mediaType = mediaType,
                    sortOrder = nextSort++,
                    playlistTarget = target
                )
                mediaDao.insertMedia(entity)
            }
            NativeWidgetVideoService.notifyPlaylistOrSettingsChanged(context)
        }

    suspend fun removeMedia(item: MediaItemEntity) = withContext(Dispatchers.IO) {
        mediaDao.deleteMedia(item)
        NativeWidgetVideoService.notifyPlaylistOrSettingsChanged(context)
    }

    suspend fun moveItem(fromPosition: Int, toPosition: Int, currentList: List<MediaItemEntity>) =
        withContext(Dispatchers.IO) {
            if (fromPosition !in currentList.indices || toPosition !in currentList.indices) return@withContext
            val mutable = currentList.toMutableList()
            val moved = mutable.removeAt(fromPosition)
            mutable.add(toPosition, moved)
            val reindexed = mutable.mapIndexed { index, item -> item.copy(sortOrder = index) }
            mediaDao.updateMedia(reindexed)
            NativeWidgetVideoService.notifyPlaylistOrSettingsChanged(context)
        }

    suspend fun cyclePlaylistTarget(item: MediaItemEntity) = withContext(Dispatchers.IO) {
        val nextTarget = when (item.playlistTarget) {
            PlaylistTarget.BOTH -> PlaylistTarget.HOME_ONLY
            PlaylistTarget.HOME_ONLY -> PlaylistTarget.LOCK_ONLY
            PlaylistTarget.LOCK_ONLY -> PlaylistTarget.BOTH
        }
        mediaDao.updateMedia(listOf(item.copy(playlistTarget = nextTarget)))
        NativeWidgetVideoService.notifyPlaylistOrSettingsChanged(context)
    }

    fun loadScaledBitmap(
        item: MediaItemEntity,
        maxWidth: Int,
        maxHeight: Int
    ): Bitmap? {
        val uri = Uri.parse(item.uriString)
        return try {
            if (item.mediaType == MediaType.VIDEO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    frame?.let { scaleDownBitmap(it, maxWidth, maxHeight) }
                } finally {
                    retriever.release()
                }
            } else {
                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, boundsOptions)
                }

                var sampleSize = 1
                while (boundsOptions.outWidth / sampleSize > maxWidth * 2 ||
                    boundsOptions.outHeight / sampleSize > maxHeight * 2
                ) {
                    sampleSize *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val raw = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOptions)
                }
                raw?.let { scaleDownBitmap(it, maxWidth, maxHeight) }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun formatBitmapForWidget(
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

    private fun scaleDownBitmap(source: Bitmap, maxW: Int, maxH: Int): Bitmap {
        if (source.width <= maxW && source.height <= maxH) return source
        val ratio = minOf(maxW.toFloat() / source.width, maxH.toFloat() / source.height)
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun resolveDisplayName(uri: Uri): String {
        var result = "Motivation Media"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) result = cursor.getString(idx)
                }
            }
        } catch (_: Exception) {}
        return result
    }
}