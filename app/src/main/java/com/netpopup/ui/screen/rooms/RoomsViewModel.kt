package com.netpopup.ui.screen.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netpopup.dtn.chat.AdmissionPolicy
import com.netpopup.dtn.chat.ChatRepository
import com.netpopup.dtn.chat.ConversationId
import com.netpopup.dtn.presentation.RoomsPresenter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Enveloppe Android de [RoomsPresenter]. */
@HiltViewModel
class RoomsViewModel @Inject constructor(repository: ChatRepository) : ViewModel() {

    private val presenter = RoomsPresenter(repository, viewModelScope)

    val uiState = presenter.state

    fun onTitleChanged(title: String) = presenter.onTitleChanged(title)
    fun onAdmissionChanged(policy: AdmissionPolicy) = presenter.onAdmissionChanged(policy)
    fun onInviteLinkChanged(link: String) = presenter.onInviteLinkChanged(link)
    fun onContactLinkChanged(link: String) = presenter.onContactLinkChanged(link)
    fun addContact() = presenter.addContact()
    fun createRoom() = presenter.createRoom()
    fun requestToJoin() = presenter.requestToJoin()
    fun approve(requestId: String) = presenter.approve(requestId)
    fun reject(requestId: String) = presenter.reject(requestId)
    fun leave(id: ConversationId) = presenter.leave(id)
    fun dismissCreatedInvite() = presenter.dismissCreatedInvite()
    fun onNavigated() = presenter.onNavigated()
    fun clearError() = presenter.clearError()
}
