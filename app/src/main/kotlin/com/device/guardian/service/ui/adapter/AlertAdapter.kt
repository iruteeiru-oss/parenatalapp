package com.device.guardian.service.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.device.guardian.service.data.model.Alert
import com.device.guardian.service.databinding.ItemAlertBinding
import org.ocpsoft.prettytime.PrettyTime
import java.util.Date

class AlertAdapter(
    private val onRead: (Alert) -> Unit
) : ListAdapter<Alert, AlertAdapter.ViewHolder>(DIFF) {

    private val prettyTime = PrettyTime()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAlertBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val b: ItemAlertBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(alert: Alert) {
            b.tvAlertReason.text = alert.reason
            b.tvAlertChat.text = "Chat: ${alert.chatName} · From: ${alert.sender}"
            b.tvAlertTime.text = prettyTime.format(Date(alert.timestamp))
            b.dotUnread.visibility = if (alert.isRead) View.GONE else View.VISIBLE

            b.root.setOnClickListener {
                if (!alert.isRead) onRead(alert)
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Alert>() {
            override fun areItemsTheSame(a: Alert, b: Alert) = a.id == b.id
            override fun areContentsTheSame(a: Alert, b: Alert) = a == b
        }
    }
}
