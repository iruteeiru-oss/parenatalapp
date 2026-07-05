package com.device.guardian.service.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.device.guardian.service.R
import com.device.guardian.service.databinding.FragmentPlatformsBinding
import com.device.guardian.service.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlatformsFragment : Fragment() {

    private var _binding: FragmentPlatformsBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: DashboardViewModel by activityViewModels {
        val parentId = requireActivity().intent.getStringExtra("parent_id") ?: "default_parent"
        DashboardViewModel.Factory(com.device.guardian.service.data.repository.MessageRepository(parentId))
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlatformsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupClickListeners()
        observeViewModel()
    }

    private fun setupClickListeners() {
        binding.cardWhatsAppPlatform.setOnClickListener {
            navigateToChats("whatsapp")
        }

        binding.cardSmsPlatform.setOnClickListener {
            navigateToChats("sms")
        }

        binding.cardCallsPlatform.setOnClickListener {
            navigateToChats("calls")
        }
    }

    private fun navigateToChats(platform: String) {
        val fragment = ChatsFragment.newInstance(platform)
        parentFragmentManager.beginTransaction()
            .replace(R.id.containerMessages, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.chatList.collectLatest { chatSummaries ->
                // Calculate WhatsApp message count
                val waCount = chatSummaries.filter { it.platform == "whatsapp" }.sumOf { it.totalCount }
                if (waCount > 0) {
                    binding.tvWhatsAppBadge.visibility = View.VISIBLE
                    binding.tvWhatsAppBadge.text = waCount.toString()
                } else {
                    binding.tvWhatsAppBadge.visibility = View.GONE
                }

                // Calculate SMS message count
                val smsCount = chatSummaries.filter { it.platform == "sms" }.sumOf { it.totalCount }
                if (smsCount > 0) {
                    binding.tvSmsBadge.visibility = View.VISIBLE
                    binding.tvSmsBadge.text = smsCount.toString()
                } else {
                    binding.tvSmsBadge.visibility = View.GONE
                }

                // Calculate Calls count
                val callsCount = chatSummaries.filter { it.platform == "calls" }.sumOf { it.totalCount }
                if (callsCount > 0) {
                    binding.tvCallsBadge.visibility = View.VISIBLE
                    binding.tvCallsBadge.text = callsCount.toString()
                } else {
                    binding.tvCallsBadge.visibility = View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
