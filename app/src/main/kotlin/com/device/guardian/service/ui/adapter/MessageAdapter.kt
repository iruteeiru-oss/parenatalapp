package com.device.guardian.service.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.device.guardian.service.data.model.Message
import com.device.guardian.service.databinding.ItemMessageBinding
import org.ocpsoft.prettytime.PrettyTime
import java.util.Date

class MessageAdapter : ListAdapter<Message, MessageAdapter.ViewHolder>(DIFF) {

    private val prettyTime = PrettyTime()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val b: ItemMessageBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(msg: Message) {
            // Sender
            b.tvSender.text = if (msg.isOutgoing) "Child → ${msg.chatName}"
                              else "${msg.sender}"

            // Chat context
            b.tvChatName.text = if (msg.isGroupChat) "Group: ${msg.chatName}"
                                else msg.chatName
            b.tvChatName.visibility = if (msg.isGroupChat) View.VISIBLE else View.GONE

            // Content
            b.tvContent.text = msg.content

            // Time
            b.tvTime.text = prettyTime.format(Date(msg.timestamp))

            // Direction tag
            b.tvDirection.text = if (msg.isOutgoing) "Sent" else "Received"

            // Flag banner
            if (msg.isFlagged) {
                b.tvFlagBanner.visibility = View.VISIBLE
                b.tvFlagBanner.text = "⚠ ${msg.flagReason ?: "Flagged"}"
                b.root.setCardBackgroundColor(Color.parseColor("#FFF8F8"))
            } else {
                b.tvFlagBanner.visibility = View.GONE
                b.root.setCardBackgroundColor(Color.WHITE)
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Message>() {
            override fun areItemsTheSame(a: Message, b: Message) = a.id == b.id
            override fun areContentsTheSame(a: Message, b: Message) = a == b
        }
    }
}
