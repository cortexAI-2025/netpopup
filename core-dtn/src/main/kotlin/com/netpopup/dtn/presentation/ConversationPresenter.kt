package com.netpopup.dtn.presentation

import com.netpopup.dtn.chat.ChatMessage
import com.netpopup.dtn.chat.ChatRepository
import com.netpopup.dtn.chat.Conversation
import com.netpopup.dtn.chat.ConversationId
import com.netpopup.dtn.chat.InvalidMessageException
import com.netpopup.dtn.chat.MessageId
import com.netpopup.dtn.chat.RoomDetails
import com.netpopup.dtn.chat.UnknownConversationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConversationUiState(
    val isLoading: Boolean = true,
    val conversation: Conversation? = null,
    /** Messages visibles : ceux des expéditeurs bloqués sont masqués (leurs enveloppes restent relayées). */
    val messages: List<ChatMessage> = emptyList(),
    val blockedSenders: Set<String> = emptySet(),
    val peersInRange: Int = 0,
    /** Membres, empreinte et rotation : room privée seulement. */
    val roomDetails: RoomDetails? = null,
    val error: String? = null
)

/**
 * Logique d'un écran de conversation (room locale, room privée ou 1:1), sans Android : le ViewModel
 * n'est qu'une enveloppe, et tout ceci se teste sur la JVM.
 *
 * @param blockedSenders empreintes des clés d'identité bloquées (jamais des pseudos).
 */
class ConversationPresenter(
    private val repository: ChatRepository,
    private val conversationId: ConversationId,
    private val scope: CoroutineScope,
    blockedSenders: Flow<Set<String>>,
    private val blockSender: suspend (String) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(ConversationUiState())
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    private var lastSentAt = Long.MIN_VALUE

    init {
        scope.launch {
            combine(
                repository.observeConversations(),
                repository.observeMessages(conversationId),
                blockedSenders,
                repository.meshStatus,
                repository.observeRoomDetails(conversationId),
            ) { conversations, messages, blocked, mesh, details ->
                val conversation = conversations.firstOrNull { it.id == conversationId }
                ConversationUiState(
                    isLoading = false,
                    conversation = conversation,
                    messages = messages.filterNot { !it.isOwn && it.senderKey in blocked },
                    blockedSenders = blocked,
                    peersInRange = mesh.peersInRange,
                    roomDetails = details,
                    error = if (conversation == null) CONVERSATION_NOT_FOUND else null,
                )
            }.collect { fresh ->
                // Une erreur d'action (envoi refusé…) survit au rafraîchissement des données.
                _state.update { old -> fresh.copy(error = fresh.error ?: old.error) }
            }
        }
    }

    fun sendMessage(text: String) {
        val now = nowMillis()
        if (lastSentAt != Long.MIN_VALUE && now - lastSentAt < MIN_INTERVAL_MS) {
            _state.update { it.copy(error = TOO_FAST) }
            return
        }
        lastSentAt = now
        scope.launch {
            try {
                repository.sendText(conversationId, text)
                _state.update { it.copy(error = null) }
            } catch (e: InvalidMessageException) {
                lastSentAt = Long.MIN_VALUE // un texte refusé ne compte pas dans le débit
                _state.update { it.copy(error = e.message ?: INVALID_MESSAGE) }
            } catch (e: UnknownConversationException) {
                _state.update { it.copy(error = CONVERSATION_NOT_FOUND) }
            }
        }
    }

    /** À appeler tant que la conversation est affichée : les messages reçus sont marqués lus. */
    fun markRead() {
        scope.launch { repository.markRead(conversationId) }
    }

    fun deleteLocally(messageId: MessageId) {
        scope.launch { repository.deleteLocally(messageId) }
    }

    fun blockSender(identityKey: String) {
        scope.launch { blockSender.invoke(identityKey) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    companion object {
        /** Un message par seconde au plus : même règle que le seau à jetons des pairs. */
        const val MIN_INTERVAL_MS = 1_000L
        const val TOO_FAST = "Please wait before sending again"
        const val INVALID_MESSAGE = "Message cannot be sent"
        const val CONVERSATION_NOT_FOUND = "Conversation not found"
    }
}
