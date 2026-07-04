package com.device.guardian.service.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.device.guardian.service.R
import com.device.guardian.service.databinding.FragmentChatsBinding
import com.device.guardian.service.ui.adapter.ChatListAdapter
import com.device.guardian.service.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChatsFragment : Fragment() {

    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!
    
    // BUG-24 fix: Provide ViewModel factory
    private val viewModel: DashboardViewModel by activityViewModels {
        val parentId = requireActivity().intent.getStringExtra("parent_id") ?: "default_parent"
        DashboardViewModel.Factory(com.device.guardian.service.data.repository.MessageRepository(parentId))
    }
    private lateinit var chatListAdapter: ChatListAdapter
    private var platform: String = "whatsapp"

    companion object {
        private const val ARG_PLATFORM = "platform"

        fun newInstance(platform: String): ChatsFragment {
            return ChatsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PLATFORM, platform)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = arguments?.getString(ARG_PLATFORM) ?: "whatsapp"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupAdapter()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.tvChatsTitle.text = when (platform) {
            "whatsapp" -> "WhatsApp Chats"
            "sms" -> "SMS Message Logs"
            "calls" -> "Calls & Log History"
            else -> "Chats"
        }

        binding.btnBackToPlatforms.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupAdapter() {
        chatListAdapter = ChatListAdapter { chatSummary ->
            navigateToConversation(chatSummary.chatName)
        }

        binding.recyclerChats.apply {
            adapter = chatListAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun navigateToConversation(chatName: String) {
        val fragment = ConversationFragment.newInstance(chatName, platform)
        parentFragmentManager.beginTransaction()
            .replace(R.id.containerMessages, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.chatList.collectLatest { chatSummaries ->
                val filtered = chatSummaries.filter { it.platform == platform }
                chatListAdapter.submitList(filtered)
                
                binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
                binding.tvEmpty.text = when (platform) {
                    "whatsapp" -> "No WhatsApp chats monitored yet"
                    "sms" -> "No SMS messages monitored yet"
                    "calls" -> "No calls logged yet"
                    else -> "No logs yet"
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
