package com.device.guardian.service.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.device.guardian.service.databinding.FragmentConversationBinding
import com.device.guardian.service.ui.adapter.MessageBubbleAdapter
import com.device.guardian.service.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ConversationFragment : Fragment() {

    private var _binding: FragmentConversationBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by activityViewModels()
    private lateinit var messageBubbleAdapter: MessageBubbleAdapter
    
    private var chatName: String = ""
    private var platform: String = "whatsapp"

    companion object {
        private const val ARG_CHAT_NAME = "chat_name"
        private const val ARG_PLATFORM = "platform"

        fun newInstance(chatName: String, platform: String): ConversationFragment {
            return ConversationFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CHAT_NAME, chatName)
                    putString(ARG_PLATFORM, platform)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatName = arguments?.getString(ARG_CHAT_NAME) ?: ""
        platform = arguments?.getString(ARG_PLATFORM) ?: "whatsapp"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConversationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupAdapter()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.tvConversationTitle.text = chatName
        binding.tvConversationSubtitle.text = when (platform) {
            "whatsapp" -> "WhatsApp Chat Log"
            "sms" -> "SMS Message Log"
            "calls" -> "Phone Call Events"
            else -> "Message Log"
        }

        binding.btnBackToChats.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupAdapter() {
        messageBubbleAdapter = MessageBubbleAdapter()
        
        binding.recyclerConversation.apply {
            adapter = messageBubbleAdapter
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true // Scroll to bottom when conversation loads
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.filteredMessages.collectLatest { messages ->
                val conversationMessages = messages
                    .filter { it.chatName == chatName && it.platform == platform }
                    .sortedBy { it.timestamp }
                
                messageBubbleAdapter.submitList(conversationMessages)
                
                binding.tvEmpty.visibility = if (conversationMessages.isEmpty()) View.VISIBLE else View.GONE
                
                // Auto scroll to latest message
                if (conversationMessages.isNotEmpty()) {
                    binding.recyclerConversation.postDelayed({
                        binding.recyclerConversation.smoothScrollToPosition(conversationMessages.size - 1)
                    }, 100)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
