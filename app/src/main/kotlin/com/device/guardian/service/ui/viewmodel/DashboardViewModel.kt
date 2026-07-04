package com.device.guardian.service.ui.viewmodel

import androidx.lifecycle.*
import com.device.guardian.service.data.model.Alert
import com.device.guardian.service.data.model.DeviceStatus
import com.device.guardian.service.data.model.Message
import com.device.guardian.service.data.repository.MessageRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DashboardViewModel(private val repo: MessageRepository) : ViewModel() {

    // ── Raw streams ────────────────────────────────────────────────────────────

    private val _allMessages = MutableStateFlow<List<Message>>(emptyList())
    private val _alerts      = MutableStateFlow<List<Alert>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    private val _isLoading   = MutableStateFlow(true)
    private val _deviceStatus = MutableStateFlow<DeviceStatus?>(null)

    // ── Public state ───────────────────────────────────────────────────────────

    val isLoading: StateFlow<Boolean> = _isLoading
    val deviceStatus: StateFlow<DeviceStatus?> = _deviceStatus
    val alerts: StateFlow<List<Alert>> = _alerts
    val unreadAlertCount: StateFlow<Int> = _alerts
        .map { list -> list.count { !it.isRead } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 0)

    // BUG-09 fix: Expose allMessages so ConversationFragment uses unfiltered data
    val allMessages: StateFlow<List<Message>> = _allMessages

    // Search-only filtered messages (no more filter chips)
    val searchedMessages: StateFlow<List<Message>> = combine(
        _allMessages, _searchQuery
    ) { messages, query ->
        if (query.isBlank()) {
            messages
        } else {
            messages.filter {
                it.content.contains(query, ignoreCase = true) ||
                it.sender.contains(query, ignoreCase = true) ||
                it.chatName.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    // Group messages by chat name and platform for the chat list view
    val chatList: StateFlow<List<ChatSummary>> = _allMessages
        .map { messages ->
            messages
                .groupBy { Pair(it.chatName, it.platform) }
                .map { (key, msgs) ->
                    ChatSummary(
                        chatName     = key.first,
                        platform     = key.second,
                        lastMessage  = msgs.first(),
                        totalCount   = msgs.size,
                        flaggedCount = msgs.count { it.isFlagged }
                    )
                }
                .sortedByDescending { it.lastMessage.timestamp }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    // ── Init ───────────────────────────────────────────────────────────────────

    init {
        observeMessages()
        observeAlerts()
        observeDeviceStatus()
    }

    private fun observeMessages() {
        viewModelScope.launch {
            repo.observeMessages()
                .catch { emit(emptyList()) }
                .collect { messages ->
                    _allMessages.value = messages
                    _isLoading.value = false
                }
        }
    }

    private fun observeAlerts() {
        viewModelScope.launch {
            repo.observeAlerts()
                .catch { emit(emptyList()) }
                .collect { _alerts.value = it }
        }
    }

    private fun observeDeviceStatus() {
        viewModelScope.launch {
            repo.observeDeviceStatus()
                .catch { emit(null) }
                .collect { _deviceStatus.value = it }
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────────

    fun setSearch(query: String) { _searchQuery.value = query }

    fun markAlertRead(alertId: String) {
        viewModelScope.launch { repo.markAlertRead(alertId) }
    }

    // ── Factory ────────────────────────────────────────────────────────────────

    class Factory(private val repo: MessageRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(repo) as T
        }
    }
}

data class ChatSummary(
    val chatName: String,
    val platform: String,
    val lastMessage: Message,
    val totalCount: Int,
    val flaggedCount: Int
)
