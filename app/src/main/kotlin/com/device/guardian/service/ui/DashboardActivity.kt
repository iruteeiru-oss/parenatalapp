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
import android.view.View
import android.net.Uri
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
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

    private var isSearchVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs()
        setupSearch()
        observeAlertBadge()
        observeDeviceStatus()
    }

    // BUG-17 fix: Use constructor fragments instead of Fragment.instantiate()
    private fun setupTabs() {
        val pagerAdapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> MessagesFragment()
                1 -> AlertsFragment()
                else -> MessagesFragment()
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

    private fun setupSearch() {
        // Search icon toggle
        binding.btnSearch.setOnClickListener {
            isSearchVisible = !isSearchVisible
            binding.searchContainer.visibility = if (isSearchVisible) View.VISIBLE else View.GONE
            if (!isSearchVisible) {
                binding.etSearch.setText("")
                viewModel.setSearch("")
            } else {
                binding.etSearch.requestFocus()
            }
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearch(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observeAlertBadge() {
        lifecycleScope.launch {
            viewModel.unreadAlertCount.collectLatest { count ->
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
                    val chargingText = if (status.isCharging) " ⚡" else ""
                    val batteryVal = if (status.batteryLevel >= 0) "${status.batteryLevel}%" else "--"
                    binding.tvStatusBattery.text = "🔋 $batteryVal$chargingText"

                    // 3. Last Known Coordinates Button
                    val lat = status.latitude
                    val lon = status.longitude
                    binding.btnShowLocation.visibility = View.VISIBLE
                    if (lat != null && lon != null) {
                        binding.btnShowLocation.text = "📍 Map"
                        binding.btnShowLocation.setTextColor(getColor(R.color.accent_blue))
                        binding.btnShowLocation.isEnabled = true
                        binding.btnShowLocation.setOnClickListener {
                            val uri = "https://www.google.com/maps/search/?api=1&query=$lat,$lon"
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                            startActivity(intent)
                        }
                    } else {
                        binding.btnShowLocation.text = "📍 N/A"
                        binding.btnShowLocation.setTextColor(getColor(R.color.text_secondary))
                        binding.btnShowLocation.isEnabled = false
                        binding.btnShowLocation.setOnClickListener(null)
                    }
                } else {
                    binding.tvStatusNetwork.text = "📶 --"
                    binding.tvStatusNetwork.setTextColor(getColor(R.color.text_secondary))
                    binding.tvStatusBattery.text = "🔋 --"
                    binding.btnShowLocation.visibility = View.GONE
                }
            }
        }
    }
}
