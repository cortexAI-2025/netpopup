package com.netpopup.ui.screen.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.netpopup.dtn.chat.ConversationId
import com.netpopup.dtn.chat.MessageId
import com.netpopup.dtn.presentation.ConversationUiState
import com.netpopup.ui.component.ChatInputBar
import com.netpopup.ui.component.MessageBubble
import com.netpopup.ui.theme.Primary
import com.netpopup.ui.util.ActiveRoomEffect
import com.netpopup.ui.util.rememberFeedbackManager
import com.netpopup.ui.util.rememberScrollManager

/**
 * Corps commun des écrans de conversation (room locale, room privée) : liste, saisie, scroll et
 * retour haptique.
 *
 *  - message envoyé par nous → toujours défiler ([ScrollManager.shouldScrollForSelf]) ;
 *  - message reçu → défiler seulement si on est déjà en bas ([ScrollManager.shouldScrollForIncoming]).
 */
@Composable
fun ConversationView(
    conversationId: ConversationId,
    state: ConversationUiState,
    emptyText: String,
    topBar: @Composable () -> Unit,
    onSend: (String) -> Unit,
    onBlockSender: (String) -> Unit,
    onDeleteLocally: (MessageId) -> Unit,
    onMarkRead: () -> Unit,
    onErrorShown: () -> Unit,
) {
    val snackbarState = remember { SnackbarHostState() }
    val scrollManager = rememberScrollManager()
    val feedbackManager = rememberFeedbackManager()

    ActiveRoomEffect(conversationId)

    // lastSeenCount distingue « nouveau message » de « chargement initial » : pure affaire d'UI.
    var lastSeenCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.messages.size) {
        val messages = state.messages
        if (messages.isEmpty()) { lastSeenCount = 0; return@LaunchedEffect }

        val isNew = messages.size > lastSeenCount
        val isSelf = messages.last().isOwn
        val isInitLoad = lastSeenCount == 0

        if (isNew && !isSelf && !isInitLoad) feedbackManager.onReceived()
        lastSeenCount = messages.size

        val shouldScroll = if (isSelf) scrollManager.shouldScrollForSelf else scrollManager.shouldScrollForIncoming
        if (shouldScroll) scrollManager.scrollToLatest(messages.size)
        onMarkRead()
    }

    LaunchedEffect(state.error) {
        state.error?.let { snackbarState.showSnackbar(it); onErrorShown() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarState) { Snackbar(snackbarData = it) } },
        topBar = topBar,
        bottomBar = { ChatInputBar(onSend = onSend, feedbackManager = feedbackManager, autoFocus = true) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Primary)

                state.messages.isEmpty() -> Text(
                    text = emptyText,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )

                else -> LazyColumn(state = scrollManager.listState, modifier = Modifier.fillMaxSize()) {
                    items(state.messages, key = { it.id.value }) { message ->
                        MessageBubble(
                            message = message,
                            conversationKind = state.conversation?.kind,
                            onBlockSender = { onBlockSender(message.senderKey) },
                            onDeleteLocally = { onDeleteLocally(message.id) }
                        )
                    }
                }
            }
        }
    }
}
