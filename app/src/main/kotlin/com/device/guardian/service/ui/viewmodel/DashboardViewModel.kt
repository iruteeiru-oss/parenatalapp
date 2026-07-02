package com.device.guardian.service.ui.viewmodel

import androidx.lifecycle.*
import com.device.guardian.service.data.model.Alert
import com.device.guardian.service.data.model.FilterState
import com.device.guardian.service.data.model.Message
import com.device.guardian.service.data.repository.MessageRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DashboardViewModel(private val repo: MessageRepository) : ViewModel() {

    // ── Raw streams ────────────────────────────────────────────────────────────

    private val _allMessages = MutableStateFlow<List<Message>>(emptyList())
    private val _alerts      = MutableStateFlow<List<Alert>>(emptyList())
    private val _filter      = MutableStateFlow(FilterState.ALL)
    private val _searchQuery = MutableStateFlow("")
    private val _isLoading   = MutableStateFlow(true)

    // ── Public state ───────────────────────────────────────────────────────────

    val isLoading: StateFlow<Boolean> = _isLoading
    val alerts: StateFlow<List<Alert>> = _alerts
    val unreadAlertCount: StateFlow<Int> = _alerts
        .map { list -> list.count { !it.isRead } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 0)

    val filteredMessages: StateFlow<List<Message>> = combine(
        _allMessages, _filter, _searchQuery
    ) { messages, filter, query ->
        var result = when (filter) {
            FilterState.ALL      -> messages
            FilterState.FLAGGED  -> messages.filter { it.isFlagged }
            FilterState.INCOMING -> messages.filter { !it.isOutgoing }
            FilterState.OUTGOING -> messages.filter { it.isOutgoing }
        }
        if (query.isNotBlank()) {
            result = result.filter {
                it.content.contains(query, ignoreCase = true) ||
                it.sender.contains(query, ignoreCase = true) ||
                it.chatName.contains(query, ignoreCase = true)
            }
        }
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    // Group messages by chat name for the chat list view
    val chatList: StateFlow<List<ChatSummary>> = _allMessages
        .map { messages ->
            messages
                .groupBy { it.chatName }
                .map { (chatName, msgs) ->
                    ChatSummary(
                        chatName    = chatName,
                        lastMessage = msgs.first(),
                        totalCount  = msgs.size,
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

    // ── Actions ────────────────────────────────────────────────────────────────

    fun setFilter(filter: FilterState) { _filter.value = filter }
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
    val lastMessage: Message,
    val totalCount: Int,
    val flaggedCount: Int
)
