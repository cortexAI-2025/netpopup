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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.netpopup.data.model.Message
import com.netpopup.ui.theme.BubbleOther
import com.netpopup.ui.theme.BubbleSelf
import com.netpopup.ui.theme.TextPrimary
import com.netpopup.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * A single message row.
 *
 * Long-press reveals a context menu for "Report" and "Block user" actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    isSelf: Boolean,
    onReport: () -> Unit,
    onBlockUser: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    val horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start
    val bubbleColor           = if (isSelf) BubbleSelf else BubbleOther
    val alignment             = if (isSelf) Alignment.End else Alignment.Start

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = horizontalArrangement
    ) {
        Box {
            Surface(
                color  = bubbleColor,
                shape  = RoundedCornerShape(
                    topStart    = if (isSelf) 16.dp else 4.dp,
                    topEnd      = if (isSelf) 4.dp else 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd   = 16.dp
                ),
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .combinedClickable(
                        onClick      = {},
                        onLongClick  = { showMenu = true }
                    )
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // Username (hidden for own messages to reduce clutter)
                    if (!isSelf) {
                        Text(
                            text  = message.username,
                            color = TextSecondary,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                        )
                    }

                    Text(
                        text  = message.content,
                        color = TextPrimary,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                    )

                    Text(
                        text      = timeFormat.format(Date(message.timestamp)),
                        color     = TextSecondary,
                        style     = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        modifier  = Modifier.align(alignment)
                    )
                }
            }

            // Context menu (long-press)
            if (!isSelf) {
                DropdownMenu(
                    expanded        = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text    = { Text("Report message") },
                        onClick = { showMenu = false; onReport() }
                    )
                    DropdownMenuItem(
                        text    = { Text("Block user") },
                        onClick = { showMenu = false; onBlockUser() }
                    )
                }
            }
        }
    }
}
