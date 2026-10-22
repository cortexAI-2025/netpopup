package com.netpopup.dtn.presentation

import com.netpopup.dtn.chat.AdmissionPolicy
import com.netpopup.dtn.chat.ChatRepository
import com.netpopup.dtn.chat.Conversation
import com.netpopup.dtn.chat.ConversationId
import com.netpopup.dtn.chat.ConversationKind
import com.netpopup.dtn.chat.InvalidRoomCodeException
import com.netpopup.dtn.chat.JoinRequest
import com.netpopup.dtn.chat.PendingJoin
import com.netpopup.dtn.chat.RoomInvite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoomsUiState(
    val isLoading: Boolean = false,
    /** Rooms privées de cet appareil. */
    val rooms: List<Conversation> = emptyList(),
    val title: String = "",
    val admission: AdmissionPolicy = AdmissionPolicy.ANY_MEMBER,
    val joinCode: String = "",
    /** Dernière room créée : son code est à montrer à ceux qui doivent la rejoindre. */
    val createdInvite: RoomInvite? = null,
    /** Demandes envoyées par cet appareil, en attente d'un membre. */
    val pendingJoins: List<PendingJoin> = emptyList(),
    /** Demandes reçues, à accepter ou refuser. */
    val joinRequests: List<JoinRequest> = emptyList(),
    val error: String? = null,
    /** Room à ouvrir, une fois : l'écran appelle [RoomsPresenter.onNavigated] après la navigation. */
    val navigateTo: ConversationId? = null
)

/** Logique de l'écran des rooms privées : créer, demander l'accès, traiter les demandes reçues. */
class RoomsPresenter(
    private val repository: ChatRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(RoomsUiState())
    val state: StateFlow<RoomsUiState> = _state.asStateFlow()

    init {
        scope.launch {
            repository.observeConversations().collect { all ->
                _state.update { it.copy(rooms = all.filter { c -> c.kind == ConversationKind.ROOM }) }
            }
        }
        scope.launch { repository.observePendingJoins().collect { p -> _state.update { it.copy(pendingJoins = p) } } }
        scope.launch { repository.observeJoinRequests().collect { r -> _state.update { it.copy(joinRequests = r) } } }
    }

    fun onTitleChanged(title: String) = _state.update { it.copy(title = title.take(MAX_TITLE)) }

    fun onAdmissionChanged(policy: AdmissionPolicy) = _state.update { it.copy(admission = policy) }

    fun onJoinCodeChanged(code: String) = _state.update { it.copy(joinCode = code.uppercase().take(MAX_CODE_INPUT)) }

    fun createRoom() {
        val title = _state.value.title.trim()
        if (title.isEmpty()) {
            _state.update { it.copy(error = TITLE_REQUIRED) }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }
        scope.launch {
            try {
                val invite = repository.createRoom(title, _state.value.admission)
                _state.update {
                    it.copy(isLoading = false, title = "", createdInvite = invite, navigateTo = invite.conversationId)
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: GENERIC_ERROR) }
            }
        }
    }

    /** Envoie une demande d'accès. Le code ne donne aucun accès : la room n'apparaît qu'une fois acceptée. */
    fun requestToJoin() {
        val code = _state.value.joinCode
        _state.update { it.copy(isLoading = true, error = null) }
        scope.launch {
            try {
                repository.requestToJoin(code)
                _state.update { it.copy(isLoading = false, joinCode = "") }
            } catch (e: InvalidRoomCodeException) {
                _state.update { it.copy(isLoading = false, error = e.message ?: INVALID_CODE) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: GENERIC_ERROR) }
            }
        }
    }

    fun approve(requestId: String) {
        scope.launch { repository.approveJoinRequest(requestId) }
    }

    fun reject(requestId: String) {
        scope.launch { repository.rejectJoinRequest(requestId) }
    }

    fun leave(id: ConversationId) {
        scope.launch { repository.leave(id) }
    }

    fun dismissCreatedInvite() = _state.update { it.copy(createdInvite = null) }

    fun onNavigated() = _state.update { it.copy(navigateTo = null) }

    fun clearError() = _state.update { it.copy(error = null) }

    companion object {
        const val MAX_TITLE = 32
        const val MAX_CODE_INPUT = 64
        const val TITLE_REQUIRED = "Give the room a name"
        const val INVALID_CODE = "Invalid room code"
        const val GENERIC_ERROR = "Something went wrong"
    }
}
