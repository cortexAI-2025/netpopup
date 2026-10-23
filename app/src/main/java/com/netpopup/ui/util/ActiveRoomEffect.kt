package com.netpopup.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.netpopup.dtn.chat.ConversationId
import com.netpopup.dtn.presentation.ActiveConversation

/**
 * Déclare [conversationId] comme la conversation à l'écran tant que l'écran est au premier plan.
 * Les notifications locales n'en émettent aucune pour elle (mesh-17).
 */
@Composable
fun ActiveRoomEffect(conversationId: ConversationId) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(conversationId, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> ActiveConversation.enter(conversationId)
                Lifecycle.Event.ON_PAUSE -> ActiveConversation.leave(conversationId)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            ActiveConversation.leave(conversationId)
        }
    }
}
