package com.netpopup.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.netpopup.dtn.chat.ChatMessage
import com.netpopup.dtn.chat.ConversationId
import com.netpopup.dtn.chat.ConversationKind
import com.netpopup.dtn.chat.DeliveryState
import com.netpopup.dtn.chat.MessageId
import com.netpopup.dtn.presentation.DeliveryDisplay
import com.netpopup.ui.theme.BubbleOther
import com.netpopup.ui.theme.BubbleSelf
import com.netpopup.ui.theme.NetPopUpTheme
import com.netpopup.ui.theme.TextPrimary
import com.netpopup.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Un formateur par appel : `SimpleDateFormat` n'est pas thread-safe et la locale peut changer en cours d'exécution. */
private fun formatTime(millis: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

/**
 * Une ligne de message.
 *
 * Nos messages affichent leur état : « Queued », « Relayed » et, en 1:1 seulement, « Delivered ».
 * L'appui long ouvre « Delete for me » et, pour les messages des autres, « Block sender » — le blocage
 * vise la clé d'identité de l'expéditeur, pas son pseudo. Il n'existe ni signalement central ni bannissement.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    conversationKind: ConversationKind?,
    onBlockSender: () -> Unit,
    onDeleteLocally: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    val isSelf = message.isOwn
    val horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start
    val bubbleColor = if (isSelf) BubbleSelf else BubbleOther
    val alignment = if (isSelf) Alignment.End else Alignment.Start
    val status = DeliveryDisplay.label(message.deliveryState, conversationKind, message.isOwn)

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = horizontalArrangement
    ) {
        Box {
            Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(
                    topStart = if (isSelf) 16.dp else 4.dp,
                    topEnd = if (isSelf) 4.dp else 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp
                ),
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .combinedClickable(onClick = {}, onLongClick = { showMenu = true })
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (!isSelf) {
                        Text(
                            text = message.displayName,
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(text = message.body, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = listOfNotNull(formatTime(message.sentAt), status).joinToString(" · "),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(alignment)
                    )
                }
            }

            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Delete for me") },
                    onClick = { showMenu = false; onDeleteLocally() }
                )
                if (!isSelf) {
                    DropdownMenuItem(
                        text = { Text("Block sender") },
                        onClick = { showMenu = false; onBlockSender() }
                    )
                }
            }
        }
    }
}

private fun sample(own: Boolean, state: DeliveryState, body: String) = ChatMessage(
    id = MessageId(body), conversationId = ConversationId("preview"), senderKey = if (own) "me" else "peer",
    displayName = if (own) "Me" else "Alice", body = body, sentAt = 1_700_000_000_000L,
    expiresAt = 1_700_000_000_000L + 6 * 3_600_000L, isOwn = own, deliveryState = state
)

@Preview(showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun MessageBubbleDirectPreview() {
    NetPopUpTheme {
        Column {
            MessageBubble(sample(false, DeliveryState.RECEIVED, "Salut !"), ConversationKind.DIRECT, {}, {})
            MessageBubble(sample(true, DeliveryState.QUEUED, "En file d'attente"), ConversationKind.DIRECT, {}, {})
            MessageBubble(sample(true, DeliveryState.RELAYED, "Transmis à un pair"), ConversationKind.DIRECT, {}, {})
            MessageBubble(sample(true, DeliveryState.ACKNOWLEDGED, "Reçu par Alice"), ConversationKind.DIRECT, {}, {})
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun MessageBubbleRoomPreview() {
    NetPopUpTheme {
        Column {
            // Dans une room, jamais « Delivered », même si l'état était acquitté.
            MessageBubble(sample(true, DeliveryState.ACKNOWLEDGED, "Message de room"), ConversationKind.ROOM, {}, {})
        }
    }
}
