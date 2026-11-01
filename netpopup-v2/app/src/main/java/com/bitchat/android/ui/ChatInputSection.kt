package com.bitchat.android.ui

import android.os.Build
import com.bitchat.android.ui.theme.BitchatFontFamily
// [Goose] Bridge file share events to ViewModel via dispatcher is installed in ChatScreen composition

// [Goose] Installing FileShareDispatcher handler in ChatScreen to forward file sends to ViewModel


import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.NearbyNotesController
import com.bitchat.android.ui.media.FullScreenImageViewer
import com.bitchat.android.ui.theme.BitchatMotion

// Conservé de l'ancien écran de discussion (ChatScreen.kt, retiré) : MeshPeerListSheet.kt l'utilise encore.
@Composable
fun ChatInputSection(
    messageText: TextFieldValue,
    onMessageTextChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onSendVoiceNote: (String?, String?, String) -> Unit,
    onSendImageNote: (String?, String?, String) -> Unit,
    onSendFileNote: (String?, String?, String) -> Unit,
    showCommandSuggestions: Boolean,
    commandSuggestions: List<CommandSuggestion>,
    showMentionSuggestions: Boolean,
    mentionSuggestions: List<String>,
    mentionPeerIdentities: Map<String, PeerIdentity> = emptyMap(),
    onCommandSuggestionClick: (CommandSuggestion) -> Unit,
    onMentionSuggestionClick: (String) -> Unit,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    colorScheme: ColorScheme,
    showMediaButtons: Boolean,
    recorderFactory: ((String?, String?) -> com.bitchat.android.features.voice.VoiceRecorder)? = null,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activePublicTalker by remember(context) {
        com.bitchat.android.features.voice.LiveVoiceManager.getInstance(context).activePublicTalker
    }.collectAsState()
    Column(
        // Flat, slightly translucent screen background — the same treatment as the top bar, so the
        // two bars are visibly the same kind of surface. No gradient: a soft ramp here just looked
        // like a smudge above a crisp hairline. The rule is inside the background so the whole bar
        // is one surface with a top border, rather than a line floating over the conversation.
        modifier = modifier
            .fillMaxWidth()
            .background(colorScheme.background.copy(alpha = BarBackgroundAlpha))
    ) {
        // Hairline marking where chrome begins. Faint on purpose — it is a hint, not a border.
        HorizontalDivider(thickness = 1.dp, color = colorScheme.outlineVariant)

        // Command suggestions box
        if (showCommandSuggestions && commandSuggestions.isNotEmpty()) {
            CommandSuggestionsBox(
                suggestions = commandSuggestions,
                onSuggestionClick = onCommandSuggestionClick,
                modifier = Modifier.fillMaxWidth()
            )
            HorizontalDivider(thickness = 1.dp, color = colorScheme.outlineVariant)
        }
        // Retain the final populated list while the picker exits. The state layer clears
        // suggestions together with visibility; without this snapshot the panel would empty and
        // snap shut before its shrink/fade animation had a frame to run.
        var retainedMentionSuggestions by remember { mutableStateOf(emptyList<String>()) }
        LaunchedEffect(mentionSuggestions) {
            if (mentionSuggestions.isNotEmpty()) {
                retainedMentionSuggestions = mentionSuggestions
            }
        }
        val mentionPickerVisible = showMentionSuggestions && mentionSuggestions.isNotEmpty()
        val displayedMentionSuggestions = mentionSuggestions.ifEmpty {
            retainedMentionSuggestions
        }

        AnimatedVisibility(
            visible = mentionPickerVisible,
            enter = fadeIn(tween(BitchatMotion.STANDARD_MS)) +
                expandVertically(
                    animationSpec = tween(
                        BitchatMotion.STANDARD_MS,
                        easing = FastOutSlowInEasing
                    ),
                    expandFrom = Alignment.Bottom
                ),
            exit = fadeOut(tween(BitchatMotion.QUICK_MS)) +
                shrinkVertically(
                    animationSpec = tween(
                        BitchatMotion.QUICK_MS,
                        easing = FastOutSlowInEasing
                    ),
                    shrinkTowards = Alignment.Bottom
                )
        ) {
            Column {
                MentionSuggestionsBox(
                    suggestions = displayedMentionSuggestions,
                    mentionPeerIdentities = mentionPeerIdentities,
                    onSuggestionClick = onMentionSuggestionClick,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(thickness = 1.dp, color = colorScheme.outlineVariant)
            }
        }
        MessageInput(
            value = messageText,
            onValueChange = onMessageTextChange,
            onSend = onSend,
            onSendVoiceNote = onSendVoiceNote,
            onSendImageNote = onSendImageNote,
            onSendFileNote = onSendFileNote,
            selectedPrivatePeer = selectedPrivatePeer,
            currentChannel = currentChannel,
            nickname = nickname,
            showMediaButtons = showMediaButtons,
            mentionPeerIdentities = mentionPeerIdentities,
            recorderFactory = recorderFactory,
            activePublicTalker = activePublicTalker,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private const val BarBackgroundAlpha = 0.88f
