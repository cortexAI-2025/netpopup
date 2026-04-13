package com.netpopup.ui.screen.`private`

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.netpopup.ui.component.ChatInputBar
import com.netpopup.ui.component.MessageBubble
import com.netpopup.ui.component.NetPopUpTopBar
import com.netpopup.ui.theme.Primary

/**
 * Écran de chat privé (room invite-only).
 *
 * Améliorations UX identiques à LocalChatScreen :
 *  - Auto-focus clavier dès l'ouverture.
 *  - Scroll intelligent (en bas OU propre message).
 *  - Haptic discret sur réception de message étranger.
 */
@Composable
fun PrivateChatScreen(
    roomId: String,
    onBack: () -> Unit,
    viewModel: PrivateChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current

    // ── Scroll intelligent ────────────────────────────────────────────────────
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            total == 0 || lastVisible >= total - 2
        }
    }

    var lastSeenCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(uiState.messages.size) {
        val messages = uiState.messages
        if (messages.isEmpty()) { lastSeenCount = 0; return@LaunchedEffect }

        val isNew      = messages.size > lastSeenCount
        val lastMsg    = messages.last()
        val isSelf     = lastMsg.userId == uiState.currentUser?.id
        val isInitLoad = lastSeenCount == 0

        // Vibration légère sur message reçu (pas au chargement initial)
        if (isNew && !isSelf && !isInitLoad) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }

        lastSeenCount = messages.size

        if (isAtBottom || isSelf) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    // ── Erreurs ───────────────────────────────────────────────────────────────
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
        topBar = {
            NetPopUpTopBar(
                title    = uiState.room?.code ?: roomId,
                subtitle = "Private Room",
                onBack   = onBack,
                actions  = {
                    val code = uiState.room?.code ?: roomId
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(code))
                    }) {
                        Icon(
                            imageVector        = Icons.Filled.ContentCopy,
                            contentDescription = "Copy code",
                            tint               = Primary
                        )
                    }
                }
            )
        },
        bottomBar = {
            ChatInputBar(onSend = viewModel::sendMessage, autoFocus = true)
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color    = Primary
                    )
                }

                uiState.messages.isEmpty() -> {
                    Text(
                        text     = "Share code \"${uiState.room?.code ?: roomId}\" to invite friends!",
                        color    = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        style    = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )
                }

                else -> {
                    LazyColumn(
                        state    = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = uiState.messages,
                            key   = { it.id }
                        ) { message ->
                            val isSelf = message.userId == uiState.currentUser?.id
                            MessageBubble(
                                message     = message,
                                isSelf      = isSelf,
                                onReport    = { viewModel.reportMessage(message.id) },
                                onBlockUser = { viewModel.blockUser(message.userId) }
                            )
                        }
                    }
                }
            }
        }
    }
}
