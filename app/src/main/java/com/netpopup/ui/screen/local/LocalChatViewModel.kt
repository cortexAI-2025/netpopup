package com.netpopup.ui.screen.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netpopup.dtn.chat.ChatRepository
import com.netpopup.dtn.chat.LOCAL_CONVERSATION_ID
import com.netpopup.dtn.chat.MessageId
import com.netpopup.dtn.presentation.ConversationPresenter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Enveloppe Android de [ConversationPresenter] pour la room locale publique. */
@HiltViewModel
class LocalChatViewModel @Inject constructor(
    private val repository: ChatRepository
) : ViewModel() {

    private val presenter = ConversationPresenter(
        repository = repository,
        conversationId = LOCAL_CONVERSATION_ID,
        scope = viewModelScope,
        blockedSenders = repository.observeBlocked(),
        blockSender = { repository.block(it) }
    )

    val uiState = presenter.state

    fun sendMessage(text: String) = presenter.sendMessage(text)
    fun markRead() = presenter.markRead()
    fun deleteLocally(id: MessageId) = presenter.deleteLocally(id)
    fun blockSender(identityKey: String) = presenter.blockSender(identityKey)
    fun clearError() = presenter.clearError()
}
