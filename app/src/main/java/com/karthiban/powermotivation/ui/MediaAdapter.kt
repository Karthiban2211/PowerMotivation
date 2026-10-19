package com.karthiban.powermotivation.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.karthiban.powermotivation.PowerMotivationApp
import com.karthiban.powermotivation.databinding.ItemMediaLibraryBinding
import com.karthiban.powermotivation.model.MediaItemEntity
import com.karthiban.powermotivation.model.MediaType
import com.karthiban.powermotivation.model.PlaylistTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaAdapter(
    private val onCycleTarget: (MediaItemEntity) -> Unit,
    private val onDelete: (MediaItemEntity) -> Unit
) : ListAdapter<MediaItemEntity, MediaAdapter.MediaViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val binding = ItemMediaLibraryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MediaViewHolder(
        private val binding: ItemMediaLibraryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaItemEntity) {
            binding.tvMediaTitle.text = item.displayName
            binding.tvMediaTypeBadge.text = if (item.mediaType == MediaType.VIDEO) "VIDEO (MP4)" else "PHOTO"

            binding.btnTargetChip.text = when (item.playlistTarget) {
                PlaylistTarget.BOTH -> "Home & Lock"
                PlaylistTarget.HOME_ONLY -> "Home Only"
                PlaylistTarget.LOCK_ONLY -> "Lock Only"
            }

            binding.btnTargetChip.setOnClickListener { onCycleTarget(item) }
            binding.btnDeleteMedia.setOnClickListener { onDelete(item) }

            CoroutineScope(Dispatchers.IO).launch {
                val thumb = PowerMotivationApp.instance.mediaRepository.loadScaledBitmap(item, 160, 160)
                withContext(Dispatchers.Main) {
                    if (thumb != null) {
                        binding.ivThumbnail.setImageBitmap(thumb)
                    } else {
                        binding.ivThumbnail.setImageResource(android.R.drawable.ic_menu_report_image)
                    }
                }
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<MediaItemEntity>() {
        override fun areItemsTheSame(oldItem: MediaItemEntity, newItem: MediaItemEntity): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: MediaItemEntity, newItem: MediaItemEntity): Boolean =
            oldItem == newItem
    }
}