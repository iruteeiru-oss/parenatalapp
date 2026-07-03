package com.device.guardian.service.ui

import android.os.Bundle
import androidx.activity.viewModels
import com.device.guardian.service.R
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.device.guardian.service.data.repository.MessageRepository
import com.device.guardian.service.databinding.ActivityDashboardBinding
import com.device.guardian.service.ui.fragment.AlertsFragment
import com.device.guardian.service.ui.fragment.MessagesFragment
import com.device.guardian.service.ui.viewmodel.DashboardViewModel
import com.google.android.material.tabs.TabLayoutMediator
import androidx.lifecycle.lifecycleScope
import com.device.guardian.service.data.model.FilterState
import android.view.View
import android.net.Uri
import android.content.Intent
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding

    private val parentId by lazy {
        intent.getStringExtra("parent_id") ?: "default_parent"
    }

    private val viewModel: DashboardViewModel by viewModels {
        DashboardViewModel.Factory(MessageRepository(parentId))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs()
        setupFilters()
        setupSearch()
        observeAlertBadge()
        observeDeviceStatus()
    }

    private fun setupTabs() {
        val pagerAdapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> Fragment.instantiate(this@DashboardActivity, MessagesFragment::class.java.name)
                1 -> Fragment.instantiate(this@DashboardActivity, AlertsFragment::class.java.name)
                else -> Fragment.instantiate(this@DashboardActivity, MessagesFragment::class.java.name)
            }
        }

        binding.viewPager.apply {
            adapter = pagerAdapter
            offscreenPageLimit = 1
        }

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Messages"
                1 -> "Alerts"
                else -> ""
            }
        }.attach()
    }

    private fun setupFilters() {
        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when {
                checkedIds.contains(binding.chipFlagged.id)  -> FilterState.FLAGGED
                checkedIds.contains(binding.chipIncoming.id) -> FilterState.INCOMING
                checkedIds.contains(binding.chipOutgoing.id) -> FilterState.OUTGOING
                else -> FilterState.ALL
            }
            viewModel.setFilter(filter)
        }
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(
            object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?) = true
                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.setSearch(newText ?: "")
                    return true
                }
            }
        )
    }

    private fun observeAlertBadge() {
        lifecycleScope.launch {
            viewModel.unreadAlertCount.collectLatest { count ->
                // Update FAB badge
                if (count > 0) {
                    binding.fabAlerts.show()
                } else {
                    binding.fabAlerts.hide()
                }
            }
        }

        binding.fabAlerts.setOnClickListener {
            binding.viewPager.currentItem = 1  // Switch to alerts tab
        }
    }

    private fun observeDeviceStatus() {
        lifecycleScope.launch {
            viewModel.deviceStatus.collectLatest { status ->
                if (status != null) {
                    // 1. Network Connectivity Status
                    val now = System.currentTimeMillis()
                    val isRecent = (now - status.timestamp) < 15 * 60 * 1000L // 15 mins
                    val effectivelyOnline = status.isOnline && isRecent
                    
                    if (effectivelyOnline) {
                        binding.tvStatusNetwork.text = "📶 Online"
                        binding.tvStatusNetwork.setTextColor(getColor(R.color.status_success))
                    } else {
                        val timeAgo = android.text.format.DateUtils.getRelativeTimeSpanString(
                            status.timestamp, now, android.text.format.DateUtils.MINUTE_IN_MILLIS
                        )
                        binding.tvStatusNetwork.text = "⚠️ Offline ($timeAgo)"
                        binding.tvStatusNetwork.setTextColor(getColor(R.color.status_error))
                    }

                    // 2. Battery Percentage & State
                    val chargingText = if (status.isCharging) " (Charging)" else ""
                    val batteryVal = if (status.batteryLevel >= 0) "${status.batteryLevel}%" else "Unknown"
                    binding.tvStatusBattery.text = "🔋 Battery: $batteryVal$chargingText"

                    // 3. Last Known Coordinates Button
                    val lat = status.latitude
                    val lon = status.longitude
                    binding.btnShowLocation.visibility = View.VISIBLE
                    if (lat != null && lon != null) {
                        binding.btnShowLocation.text = "📍 Show on Map"
                        binding.btnShowLocation.setTextColor(getColor(android.R.color.holo_blue_dark))
                        binding.btnShowLocation.isEnabled = true
                        binding.btnShowLocation.setOnClickListener {
                            val uri = "https://www.google.com/maps/search/?api=1&query=$lat,$lon"
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                            startActivity(intent)
                        }
                    } else {
                        binding.btnShowLocation.text = "📍 Loc Unavailable"
                        binding.btnShowLocation.setTextColor(getColor(android.R.color.darker_gray))
                        binding.btnShowLocation.isEnabled = false
                        binding.btnShowLocation.setOnClickListener(null)
                    }
                } else {
                    binding.tvStatusNetwork.text = "📶 Connectivity: --"
                    binding.tvStatusNetwork.setTextColor(getColor(android.R.color.darker_gray))
                    binding.tvStatusBattery.text = "🔋 Battery: --"
                    binding.btnShowLocation.visibility = View.GONE
                }
            }
        }
    }
}
