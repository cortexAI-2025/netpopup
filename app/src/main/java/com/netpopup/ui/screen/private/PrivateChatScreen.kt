package com.netpopup.ui.screen.`private`

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.netpopup.ui.theme.TextSecondary

/**
 * Private (invite-only) room chat screen.
 *
 * A "copy code" icon in the top bar lets the host share the room code
 * out-of-band easily.
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
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
        topBar = {
            NetPopUpTopBar(
                title    = uiState.room?.code ?: roomId,
                subtitle = "Private Room",
                onBack   = onBack,
                actions  = {
                    // Copy room code to clipboard
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
            ChatInputBar(onSend = viewModel::sendMessage)
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
