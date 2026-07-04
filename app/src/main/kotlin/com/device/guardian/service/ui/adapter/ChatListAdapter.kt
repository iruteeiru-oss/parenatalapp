package com.device.guardian.service.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.device.guardian.service.ui.viewmodel.ChatSummary
import com.device.guardian.service.databinding.ItemChatProfileBinding
import org.ocpsoft.prettytime.PrettyTime
import java.util.Date

class ChatListAdapter(
    private val onChatClick: (ChatSummary) -> Unit
) : ListAdapter<ChatSummary, ChatListAdapter.ViewHolder>(DIFF) {

    private val prettyTime = PrettyTime()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChatProfileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val b: ItemChatProfileBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(summary: ChatSummary) {
            b.tvChatName.text = summary.chatName
            b.tvLastMessage.text = summary.lastMessage.content
            b.tvLastTime.text = prettyTime.format(Date(summary.lastMessage.timestamp))
            
            // Set first letter as avatar
            val firstLetter = if (summary.chatName.isNotBlank()) {
                summary.chatName.trim().first().toString().uppercase()
            } else {
                "?"
            }
            b.tvAvatarText.text = firstLetter

            if (summary.totalCount > 0) {
                b.tvMessageCount.visibility = View.VISIBLE
                b.tvMessageCount.text = summary.totalCount.toString()
            } else {
                b.tvMessageCount.visibility = View.GONE
            }

            b.root.setOnClickListener {
                onChatClick(summary)
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ChatSummary>() {
            override fun areItemsTheSame(a: ChatSummary, b: ChatSummary) = a.chatName == b.chatName && a.platform == b.platform
            override fun areContentsTheSame(a: ChatSummary, b: ChatSummary) = a == b
        }
    }
}
