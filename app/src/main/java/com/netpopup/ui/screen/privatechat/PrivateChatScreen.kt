package com.netpopup.ui.screen.privatechat

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import com.netpopup.dtn.chat.RoomDetails
import com.netpopup.ui.component.NetPopUpTopBar
import com.netpopup.ui.screen.conversation.ConversationView
import com.netpopup.ui.theme.Primary

/**
 * Room privée : messages, état de livraison, et en haut l'état du chiffrement. Le bouton « i » ouvre
 * les membres et l'empreinte de sécurité, à comparer en personne. « Key rotation in progress »
 * s'affiche après un retrait de membre, tant que les nouvelles clés ne sont pas toutes en place.
 */
@Composable
fun PrivateChatScreen(
    roomId: String,
    onBack: () -> Unit,
    viewModel: PrivateChatViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showDetails by remember { mutableStateOf(false) }
    val details = state.roomDetails
    val title = state.conversation?.title ?: roomId
    val subtitle = if (details?.keyRotationPending == true) "Private room · Key rotation in progress" else "Private room"

    ConversationView(
        conversationId = viewModel.conversationId,
        state = state,
        emptyText = "No messages yet.\nOnly members of this room can read it.",
        topBar = {
            NetPopUpTopBar(
                title = title,
                subtitle = subtitle,
                onBack = onBack,
                actions = {
                    if (details != null) {
                        IconButton(onClick = { showDetails = true }) {
                            Icon(Icons.Filled.Info, "Members and security", tint = Primary)
                        }
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

    if (showDetails && details != null) {
        RoomDetailsDialog(title, details) { showDetails = false }
    }
}

@Composable
private fun RoomDetailsDialog(title: String, details: RoomDetails, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(title) },
        text = {
            Column {
                Text("Members (${details.members.size})", fontWeight = FontWeight.Bold)
                details.members.forEach { Text(if (it.isSelf) "${it.displayName} (you)" else it.displayName) }
                Text("Security fingerprint", fontWeight = FontWeight.Bold)
                Text(details.securityFingerprint, fontFamily = FontFamily.Monospace)
                Text("Compare it in person with another member.")
                if (details.keyRotationPending) Text("Key rotation in progress: new keys are being shared.")
            }
        }
    )
}
