package com.device.guardian.service.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.device.guardian.service.databinding.FragmentMessagesBinding
import com.device.guardian.service.ui.adapter.ChatListAdapter
import com.device.guardian.service.ui.adapter.MessageBubbleAdapter
import com.device.guardian.service.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MessagesFragment : Fragment() {

    private var _binding: FragmentMessagesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by activityViewModels {
        val parentId = requireActivity().intent.getStringExtra("parent_id") ?: "default_parent"
        DashboardViewModel.Factory(com.device.guardian.service.data.repository.MessageRepository(parentId))
    }

    private lateinit var chatListAdapter: ChatListAdapter
    private lateinit var messageBubbleAdapter: MessageBubbleAdapter

    private var currentState = 0 // 0: Platforms, 1: Chats, 2: Conversation
    private var selectedChatName: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupAdapters()
        setupClickListeners()
        observeViewModel()
        updateUiState()
    }

    private fun setupAdapters() {
        chatListAdapter = ChatListAdapter { chatSummary ->
            selectedChatName = chatSummary.chatName
            currentState = 2
            binding.tvConversationTitle.text = chatSummary.chatName
            updateUiState()
            
            // Trigger filter update to refresh message list for this conversation
            refreshConversationList()
        }

        messageBubbleAdapter = MessageBubbleAdapter()

        binding.recyclerChats.apply {
            adapter = chatListAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        binding.recyclerConversation.apply {
            adapter = messageBubbleAdapter
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true // Scroll to bottom when conversation loads
            }
        }
    }

    private fun setupClickListeners() {
        binding.cardWhatsAppPlatform.setOnClickListener {
            currentState = 1
            updateUiState()
        }

        binding.btnBackToPlatforms.setOnClickListener {
            currentState = 0
            updateUiState()
        }

        binding.btnBackToChats.setOnClickListener {
            currentState = 1
            selectedChatName = null
            updateUiState()
        }
    }

    private fun observeViewModel() {
        // Observe platforms metadata / message count
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.chatList.collectLatest { chatSummaries ->
                val totalMessages = chatSummaries.sumOf { it.totalCount }
                if (totalMessages > 0) {
                    binding.tvWhatsAppBadge.visibility = View.VISIBLE
                    binding.tvWhatsAppBadge.text = totalMessages.toString()
                } else {
                    binding.tvWhatsAppBadge.visibility = View.GONE
                }
                
                chatListAdapter.submitList(chatSummaries)
                
                if (currentState == 1) {
                    binding.tvEmpty.visibility = if (chatSummaries.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        // Observe messages stream for active conversation
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.filteredMessages.collectLatest { messages ->
                refreshConversationList(messages)
            }
        }

        // Observe loading state
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }
    }

    private fun refreshConversationList(messagesList: List<com.device.guardian.service.data.model.Message>? = null) {
        val chatName = selectedChatName ?: return
        val list = messagesList ?: viewModel.filteredMessages.value
        
        // Filter and sort oldest-to-newest for linear conversation flow
        val conversationMessages = list
            .filter { it.chatName == chatName }
            .sortedBy { it.timestamp }
            
        messageBubbleAdapter.submitList(conversationMessages)
        
        if (currentState == 2) {
            binding.tvEmpty.visibility = if (conversationMessages.isEmpty()) View.VISIBLE else View.GONE
            
            // Auto scroll to latest message
            if (conversationMessages.isNotEmpty()) {
                binding.recyclerConversation.postDelayed({
                    binding.recyclerConversation.smoothScrollToPosition(conversationMessages.size - 1)
                }, 100)
            }
        }
    }

    private fun updateUiState() {
        binding.layoutPlatforms.visibility = if (currentState == 0) View.VISIBLE else View.GONE
        binding.layoutChats.visibility = if (currentState == 1) View.VISIBLE else View.GONE
        binding.layoutConversation.visibility = if (currentState == 2) View.VISIBLE else View.GONE

        // Handle empty view visibility
        if (currentState == 0) {
            binding.tvEmpty.visibility = View.GONE
        } else if (currentState == 1) {
            binding.tvEmpty.visibility = if (chatListAdapter.currentList.isEmpty()) View.VISIBLE else View.GONE
            binding.tvEmpty.text = "No chats monitored yet"
        } else if (currentState == 2) {
            binding.tvEmpty.text = "No messages in this chat"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
