package com.device.guardian.service.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.device.guardian.service.data.model.Message
import com.device.guardian.service.databinding.ItemMessageBubbleBinding
import java.text.DateFormat
import java.util.Date

class MessageBubbleAdapter : ListAdapter<Message, MessageBubbleAdapter.ViewHolder>(DIFF) {

    private val timeFormatter = DateFormat.getTimeInstance(DateFormat.SHORT)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMessageBubbleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val b: ItemMessageBubbleBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(msg: Message) {
            val formattedTime = timeFormatter.format(Date(msg.timestamp))

            if (msg.isOutgoing) {
                b.layoutOutgoing.visibility = View.VISIBLE
                b.layoutIncoming.visibility = View.GONE

                b.tvOutgoingContent.text = msg.content
                b.tvOutgoingTime.text = formattedTime
                b.tvOutgoingFlag.visibility = if (msg.isFlagged) View.VISIBLE else View.GONE
            } else {
                b.layoutIncoming.visibility = View.VISIBLE
                b.layoutOutgoing.visibility = View.GONE

                b.tvIncomingContent.text = msg.content
                b.tvIncomingTime.text = formattedTime
                b.tvIncomingFlag.visibility = if (msg.isFlagged) View.VISIBLE else View.GONE

                // Show sender name if it's a group chat
                if (msg.isGroupChat && msg.sender.isNotBlank()) {
                    b.tvIncomingSender.visibility = View.VISIBLE
                    b.tvIncomingSender.text = msg.sender
                } else {
                    b.tvIncomingSender.visibility = View.GONE
                }
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
