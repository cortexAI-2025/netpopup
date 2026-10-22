package com.netpopup.ui.screen.local

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.netpopup.dtn.chat.LOCAL_CONVERSATION_ID
import com.netpopup.ui.component.NetPopUpTopBar
import com.netpopup.ui.screen.conversation.ConversationView
import com.netpopup.ui.theme.Primary

/**
 * Room locale : ouverte à tous les appareils à portée, donc publique. Le mot « Public » reste
 * affiché en permanence, avec le nombre de pairs à portée.
 */
@Composable
fun LocalChatScreen(
    onNavigateToRooms: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: LocalChatViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val nearby = if (state.peersInRange > 0) "${state.peersInRange} nearby" else "No one nearby"

    ConversationView(
        conversationId = LOCAL_CONVERSATION_ID,
        state = state,
        emptyText = "No messages yet.\nAnyone nearby can read this room.",
        topBar = {
            NetPopUpTopBar(
                showBrandLogo = true,
                subtitle = "Public room · $nearby",
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, "Settings", tint = Primary)
                    }
                    IconButton(onClick = onNavigateToRooms) {
                        Icon(Icons.Filled.Lock, "Private Rooms", tint = Primary)
                    }
                }
            )
        },
        onSend = viewModel::sendMessage,
        onBlockSender = viewModel::blockSender,
        onDeleteLocally = viewModel::deleteLocally,
        onMarkRead = viewModel::markRead,
        onErrorShown = viewModel::clearError
    )
}
