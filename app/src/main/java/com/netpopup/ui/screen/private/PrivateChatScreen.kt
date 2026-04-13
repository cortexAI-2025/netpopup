package com.netpopup.ui.screen.`private`

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.netpopup.ui.component.ChatInputBar
import com.netpopup.ui.component.MessageBubble
import com.netpopup.ui.component.NetPopUpTopBar
import com.netpopup.ui.theme.Primary
import com.netpopup.ui.util.rememberFeedbackManager
import com.netpopup.ui.util.rememberScrollManager

/**
 * Écran de chat privé (room invite-only).
 *
 * Même architecture scroll + haptic que LocalChatScreen :
 *  - [ScrollManager]  gère isAtBottom pixel-based et les règles de scroll.
 *  - [FeedbackManager] gère tout le feedback sensoriel.
 */
@Composable
fun PrivateChatScreen(
    roomId: String,
    onBack: () -> Unit,
    viewModel: PrivateChatViewModel = hiltViewModel()
) {
    val uiState         by viewModel.uiState.collectAsState()
    val snackbarState   = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val scrollManager   = rememberScrollManager()
    val feedbackManager = rememberFeedbackManager()

    // ── Scroll + Haptic ───────────────────────────────────────────────────────
    var lastSeenCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(uiState.messages.size) {
        val messages    = uiState.messages
        if (messages.isEmpty()) { lastSeenCount = 0; return@LaunchedEffect }

        val isNew       = messages.size > lastSeenCount
        val isSelf      = messages.last().userId == uiState.currentUser?.id
        val isInitLoad  = lastSeenCount == 0

        if (isNew && !isSelf && !isInitLoad) feedbackManager.onReceived()

        lastSeenCount = messages.size

        val shouldScroll = if (isSelf) scrollManager.shouldScrollForSelf
                           else        scrollManager.shouldScrollForIncoming

        if (shouldScroll) scrollManager.scrollToLatest(messages.size)
    }

    // ── Erreurs ───────────────────────────────────────────────────────────────
    LaunchedEffect(uiState.error) {
        uiState.error?.let { snackbarState.showSnackbar(it); viewModel.clearError() }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarState) { Snackbar(snackbarData = it) } },
        topBar = {
            NetPopUpTopBar(
                title    = uiState.room?.code ?: roomId,
                subtitle = "Private Room",
                onBack   = onBack,
                actions  = {
                    val code = uiState.room?.code ?: roomId
                    IconButton(onClick = { clipboardManager.setText(AnnotatedString(code)) }) {
                        Icon(Icons.Filled.ContentCopy, "Copy code", tint = Primary)
                    }
                }
            )
        },
        bottomBar = {
            ChatInputBar(
                onSend          = viewModel::sendMessage,
                feedbackManager = feedbackManager,
                autoFocus       = true
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                uiState.isLoading -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center), color = Primary
                )

                uiState.messages.isEmpty() -> Text(
                    text     = "Share code \"${uiState.room?.code ?: roomId}\" to invite friends!",
                    color    = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                    style    = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )

                else -> LazyColumn(
                    state    = scrollManager.listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        MessageBubble(
                            message     = message,
                            isSelf      = message.userId == uiState.currentUser?.id,
                            onReport    = { viewModel.reportMessage(message.id) },
                            onBlockUser = { viewModel.blockUser(message.userId) }
                        )
                    }
                }
            }
        }
    }
}
