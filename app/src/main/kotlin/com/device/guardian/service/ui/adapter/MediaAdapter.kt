package com.device.guardian.service.ui.adapter

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.device.guardian.service.R
import com.device.guardian.service.databinding.ItemMediaBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MediaItem(
    val fileId: String,
    val fileName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val mimeType: String,
    val requestStatus: String,
    val downloadUrl: String? = null
)

class MediaAdapter(
    private val onRequestClick: (String) -> Unit,
    private val onViewClick: (String) -> Unit
) : ListAdapter<MediaItem, MediaAdapter.ViewHolder>(MediaDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMediaBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaItem) {
            binding.tvFileName.text = item.fileName
            
            val sizeMb = String.format(Locale.US, "%.2f MB", item.sizeBytes / (1024.0 * 1024.0))
            val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(Date(item.lastModified))
            binding.tvMetadata.text = "$sizeMb • $dateStr"
            
            // Icon based on type
            when {
                item.mimeType.startsWith("video/") -> binding.ivIcon.setImageResource(android.R.drawable.presence_video_online)
                item.mimeType.startsWith("image/") -> binding.ivIcon.setImageResource(android.R.drawable.ic_menu_gallery)
                else -> binding.ivIcon.setImageResource(android.R.drawable.ic_menu_report_image)
            }
            
            // Button state
            when (item.requestStatus) {
                "AVAILABLE" -> {
                    binding.btnAction.text = "Request"
                    binding.btnAction.setBackgroundColor(binding.root.context.getColor(R.color.accent_blue))
                    binding.btnAction.isEnabled = true
                    binding.btnAction.setOnClickListener { onRequestClick(item.fileId) }
                }
                "REQUESTED", "UPLOADING" -> {
                    binding.btnAction.text = "Uploading..."
                    binding.btnAction.setBackgroundColor(binding.root.context.getColor(R.color.text_secondary))
                    binding.btnAction.isEnabled = false
                    binding.btnAction.setOnClickListener(null)
                }
                "UPLOADED" -> {
                    binding.btnAction.text = "View"
                    binding.btnAction.setBackgroundColor(binding.root.context.getColor(R.color.status_success))
                    binding.btnAction.isEnabled = true
                    binding.btnAction.setOnClickListener {
                        item.downloadUrl?.let { url -> onViewClick(url) }
                    }
                }
                else -> {
                    binding.btnAction.text = "Failed"
                    binding.btnAction.setBackgroundColor(binding.root.context.getColor(R.color.status_error))
                    binding.btnAction.isEnabled = true
                    binding.btnAction.setOnClickListener { onRequestClick(item.fileId) } // Retry
                }
            }
        }
    }

    class MediaDiffCallback : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem) = oldItem.fileId == newItem.fileId
        override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem) = oldItem == newItem
    }
}
