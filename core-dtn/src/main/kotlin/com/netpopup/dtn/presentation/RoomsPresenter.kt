package com.netpopup.dtn.presentation

import com.netpopup.dtn.chat.AdmissionPolicy
import com.netpopup.dtn.chat.ChatRepository
import com.netpopup.dtn.chat.Conversation
import com.netpopup.dtn.chat.ConversationId
import com.netpopup.dtn.chat.ConversationKind
import com.netpopup.dtn.chat.InvalidInviteException
import com.netpopup.dtn.chat.JoinRequest
import com.netpopup.dtn.chat.PendingJoin
import com.netpopup.dtn.chat.RoomInvite
import kotlinx.coroutines.CancellationException
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
    /** Existing direct conversations remain reachable after leaving the chat or restarting. */
    val contacts: List<Conversation> = emptyList(),
    val title: String = "",
    val admission: AdmissionPolicy = AdmissionPolicy.ANY_MEMBER,
    /** Lien d'invitation collé ou scanné pour demander l'accès à une room. */
    val inviteLink: String = "",
    /** Notre lien de contact, à montrer en QR ou à copier. */
    val myContactLink: String? = null,
    /** Lien de contact d'un tiers, collé ou scanné. */
    val contactLink: String = "",
    /** Dernière room créée : son lien est à partager avec ceux qui doivent la rejoindre. */
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
        launchAction {
            repository.observeConversations().collect { all ->
                _state.update { it.copy(
                    rooms = all.filter { c -> c.kind == ConversationKind.ROOM },
                    contacts = all.filter { c -> c.kind == ConversationKind.DIRECT },
                ) }
            }
        }
        scope.launch {
            try {
                val link = repository.myContactLink()
                _state.update { it.copy(myContactLink = link) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Le lien de contact est un confort : l'écran reste utilisable sans.
            }
        }
        launchAction { repository.observePendingJoins().collect { p -> _state.update { it.copy(pendingJoins = p) } } }
        launchAction { repository.observeJoinRequests().collect { r -> _state.update { it.copy(joinRequests = r) } } }
    }

    fun onTitleChanged(title: String) = _state.update { it.copy(title = title.take(MAX_TITLE)) }

    fun onAdmissionChanged(policy: AdmissionPolicy) = _state.update { it.copy(admission = policy) }

    fun onInviteLinkChanged(link: String) = _state.update { it.copy(inviteLink = link.take(MAX_LINK_INPUT)) }

    fun onContactLinkChanged(link: String) = _state.update { it.copy(contactLink = link.take(MAX_LINK_INPUT)) }

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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: GENERIC_ERROR) }
            }
        }
    }

    /** Envoie une demande d'accès. Le lien ne donne aucun accès : la room n'apparaît qu'une fois acceptée. */
    fun requestToJoin() {
        val link = _state.value.inviteLink
        _state.update { it.copy(isLoading = true, error = null) }
        scope.launch {
            try {
                repository.requestToJoin(link)
                _state.update { it.copy(isLoading = false, inviteLink = "") }
            } catch (e: InvalidInviteException) {
                _state.update { it.copy(isLoading = false, error = e.message ?: INVALID_LINK) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: GENERIC_ERROR) }
            }
        }
    }

    /** Ajoute un contact depuis son lien et ouvre la conversation. */
    fun addContact() {
        val link = _state.value.contactLink
        _state.update { it.copy(isLoading = true, error = null) }
        scope.launch {
            try {
                val id = repository.addContact(link)
                _state.update { it.copy(isLoading = false, contactLink = "", navigateTo = id) }
            } catch (e: InvalidInviteException) {
                _state.update { it.copy(isLoading = false, error = e.message ?: INVALID_LINK) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: GENERIC_ERROR) }
            }
        }
    }

    fun approve(requestId: String) {
        launchAction { repository.approveJoinRequest(requestId) }
    }

    fun reject(requestId: String) {
        launchAction { repository.rejectJoinRequest(requestId) }
    }

    fun leave(id: ConversationId) {
        launchAction { repository.leave(id) }
    }

    private fun launchAction(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = GENERIC_ERROR) }
            }
        }
    }

    fun dismissCreatedInvite() = _state.update { it.copy(createdInvite = null) }

    fun onNavigated() = _state.update { it.copy(navigateTo = null) }

    fun clearError() = _state.update { it.copy(error = null) }

    companion object {
        const val MAX_TITLE = 32
        const val MAX_LINK_INPUT = 3_000
        const val TITLE_REQUIRED = "Give the room a name"
        const val INVALID_LINK = "Invalid link"
        const val GENERIC_ERROR = "Something went wrong"
    }
}
