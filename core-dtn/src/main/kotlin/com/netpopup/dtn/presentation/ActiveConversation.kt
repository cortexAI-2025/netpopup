package com.netpopup.dtn.presentation

import com.netpopup.dtn.chat.ConversationId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Conversation actuellement à l'écran. Les notifications (mesh-17) ne signalent rien pour elle :
 * l'utilisateur voit déjà ses messages arriver.
 */
object ActiveConversation {
    private val _current = MutableStateFlow<ConversationId?>(null)
    val current: StateFlow<ConversationId?> = _current.asStateFlow()

    fun enter(id: ConversationId) {
        _current.value = id
    }

    /** Sans effet si une autre conversation a pris la place entre-temps (navigation rapide). */
    fun leave(id: ConversationId) {
        _current.compareAndSet(id, null)
    }

    fun isShown(id: ConversationId): Boolean = _current.value == id
}
