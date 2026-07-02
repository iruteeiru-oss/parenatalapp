package com.device.guardian.service.ui

import android.os.Bundle
import androidx.activity.viewModels
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
    }

    private fun setupTabs() {
        val pagerAdapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> MessagesFragment()
                1 -> AlertsFragment()
                else -> MessagesFragment()
            }
        }

        binding.viewPager.adapter = pagerAdapter

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
}
