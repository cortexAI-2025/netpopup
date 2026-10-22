package com.netpopup.ui.screen.privatechat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netpopup.dtn.chat.ChatRepository
import com.netpopup.dtn.chat.ConversationId
import com.netpopup.dtn.chat.MessageId
import com.netpopup.dtn.presentation.ConversationPresenter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Enveloppe Android de [ConversationPresenter] pour une room privée ou un 1:1.
 * L'identifiant de conversation vient de l'argument de navigation `roomId`.
 */
@HiltViewModel
class PrivateChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ChatRepository
) : ViewModel() {

    val conversationId = ConversationId(checkNotNull(savedStateHandle["roomId"]))

    private val presenter = ConversationPresenter(
        repository = repository,
        conversationId = conversationId,
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
