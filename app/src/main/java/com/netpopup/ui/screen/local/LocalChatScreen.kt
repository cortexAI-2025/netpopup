package com.netpopup.ui.screen.local

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.netpopup.ui.component.ChatInputBar
import com.netpopup.ui.component.MessageBubble
import com.netpopup.ui.component.NetPopUpTopBar
import com.netpopup.ui.theme.Primary
import com.netpopup.ui.util.rememberFeedbackManager
import com.netpopup.ui.util.rememberScrollManager

/**
 * Local geo-based chat screen.
 *
 * Logique de scroll et de feedback entièrement déléguée à :
 *  - [ScrollManager]  : décision de scroll (pixel-based, comportements séparés)
 *  - [FeedbackManager]: toute la couche haptique
 *
 * Règles de scroll appliquées ici :
 *  - self message  → [ScrollManager.shouldScrollForSelf]    (toujours vrai)
 *  - reçu entrant  → [ScrollManager.shouldScrollForIncoming] (si en bas)
 */
@Composable
fun LocalChatScreen(
    onNavigateToRooms: () -> Unit,
    viewModel: LocalChatViewModel = hiltViewModel()
) {
    val uiState         by viewModel.uiState.collectAsState()
    val snackbarState   = remember { SnackbarHostState() }
    val scrollManager   = rememberScrollManager()        // pixel-based isAtBottom
    val feedbackManager = rememberFeedbackManager()      // toda haptique centralisée

    // ── Location permission ───────────────────────────────────────────────────
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* résultat géré dans le ViewModel */ }

    LaunchedEffect(Unit) {
        locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    // ── Scroll + Haptic ───────────────────────────────────────────────────────
    // lastSeenCount sert uniquement à détecter « nouveau message » vs
    // « chargement initial » — il n'est PAS dans le UiState car c'est une
    // préoccupation purement UI.
    var lastSeenCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(uiState.messages.size) {
        val messages    = uiState.messages
        if (messages.isEmpty()) { lastSeenCount = 0; return@LaunchedEffect }

        val isNew       = messages.size > lastSeenCount
        val isSelf      = messages.last().userId == uiState.currentUser?.id
        val isInitLoad  = lastSeenCount == 0

        // ── Haptic : UNIQUEMENT sur message reçu d'un autre (pas chargement) ─
        if (isNew && !isSelf && !isInitLoad) feedbackManager.onReceived()

        lastSeenCount = messages.size

        // ── Scroll : comportements clairement séparés ─────────────────────────
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
                showBrandLogo = true,           // logo secondaire (bulle + NetPopUp)
                subtitle      = if (uiState.zoneDisplay.isNotBlank()) "Zone ${uiState.zoneDisplay}" else null,
                actions       = {
                    IconButton(onClick = onNavigateToRooms) {
                        Icon(Icons.Filled.Lock, "Private Rooms", tint = Primary)
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
                    text     = "No messages yet.\nBe the first to say something!",
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
