package com.netpopup.ui.screen.privatechat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netpopup.data.local.UserPreferences
import com.netpopup.data.model.Message
import com.netpopup.data.model.Room
import com.netpopup.data.model.User
import com.netpopup.data.repository.AuthRepository
import com.netpopup.data.repository.MessageRepository
import com.netpopup.data.repository.RoomRepository
import com.netpopup.util.ModerationUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PrivateChatUiState(
    val isLoading: Boolean          = true,
    val currentUser: User?          = null,
    val room: Room?                 = null,
    val messages: List<Message>     = emptyList(),
    val blockedUsers: Set<String>   = emptySet(),
    val error: String?              = null
)

/**
 * ViewModel for a private room chat screen.
 *
 * [roomId] is injected via [SavedStateHandle] — Hilt + Navigation Compose
 * automatically populate it from the nav argument.
 */
@HiltViewModel
class PrivateChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    private val messageRepository: MessageRepository,
    private val roomRepository: RoomRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val roomId: String = checkNotNull(savedStateHandle["roomId"])

    private val _uiState = MutableStateFlow(PrivateChatUiState())
    val uiState: StateFlow<PrivateChatUiState> = _uiState.asStateFlow()

    private var lastSentAt = 0L

    init {
        viewModelScope.launch { initialize() }
    }

    private suspend fun initialize() {
        try {
            val user = authRepository.ensureSignedIn()
            _uiState.update { it.copy(currentUser = user) }

            // The room document already exists (created in RoomsScreen)
            val room = roomRepository.joinPrivateRoom(roomId)
                ?: throw IllegalStateException("Room $roomId not found")

            _uiState.update { it.copy(room = room, isLoading = false) }

            combine(
                messageRepository.observeMessages(room.id),
                userPreferences.blockedUsersFlow
            ) { messages, blocked ->
                messages.filter { it.userId !in blocked } to blocked
            }.collect { (filteredMessages, blocked) ->
                _uiState.update {
                    it.copy(messages = filteredMessages, blockedUsers = blocked)
                }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    fun sendMessage(content: String) {
        val now = System.currentTimeMillis()
        if (now - lastSentAt < 1_000L) {
            _uiState.update { it.copy(error = "Please wait before sending again") }
            return
        }

        val user   = _uiState.value.currentUser ?: return
        val rId    = _uiState.value.room?.id    ?: return
        val sanitized = ModerationUtils.sanitize(content)

        if (sanitized.isBlank()) return
        if (sanitized.length > Message.MAX_LENGTH) {
            _uiState.update { it.copy(error = "Message too long") }
            return
        }
        if (ModerationUtils.containsBannedWords(sanitized)) {
            _uiState.update { it.copy(error = "Message contains prohibited content") }
            return
        }

        lastSentAt = now
        clearError()

        viewModelScope.launch {
            try {
                messageRepository.sendMessage(
                    roomId   = rId,
                    userId   = user.id,
                    username = user.username,
                    content  = sanitized
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to send: ${e.message}") }
            }
        }
    }

    fun reportMessage(messageId: String) {
        viewModelScope.launch {
            runCatching { messageRepository.reportMessage(messageId) }
            _uiState.update { it.copy(error = "Message reported") }
        }
    }

    fun blockUser(userId: String) {
        viewModelScope.launch { userPreferences.blockUser(userId) }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
