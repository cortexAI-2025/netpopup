package com.netpopup.ui.screen.local

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.netpopup.ui.component.ChatInputBar
import com.netpopup.ui.component.MessageBubble
import com.netpopup.ui.component.NetPopUpTopBar
import com.netpopup.ui.theme.Primary

/**
 * Local geo-based chat screen.
 *
 * Améliorations UX :
 *  - Auto-focus clavier via ChatInputBar(autoFocus=true).
 *  - Scroll intelligent : descend automatiquement seulement si l'utilisateur
 *    est déjà en bas (≤ 2 items du dernier) OU si c'est son propre message.
 *  - Haptic "réception" (TextHandleMove, discret) quand un message étranger
 *    arrive — ne se déclenche pas au chargement initial.
 */
@Composable
fun LocalChatScreen(
    onNavigateToRooms: () -> Unit,
    viewModel: LocalChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    // ── Location permission ───────────────────────────────────────────────────
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* résultat géré dans le ViewModel via FusedLocationClient */ }

    LaunchedEffect(Unit) {
        locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    // ── Scroll intelligent ────────────────────────────────────────────────────
    // Vrai si le dernier élément visible est à ≤ 2 items de la fin.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = info.totalItemsCount
            total == 0 || lastVisible >= total - 2
        }
    }

    // Mémorise combien de messages ont été vus pour détecter les nouveaux.
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

        // Scroll uniquement si en bas OU si c'est notre propre envoi
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
                title    = "Local Chat",
                subtitle = if (uiState.zoneDisplay.isNotBlank()) "Zone ${uiState.zoneDisplay}" else null,
                actions  = {
                    IconButton(onClick = onNavigateToRooms) {
                        Icon(
                            imageVector        = Icons.Filled.Lock,
                            contentDescription = "Private Rooms",
                            tint               = Primary
                        )
                    }
                }
            )
        },
        bottomBar = {
            // autoFocus=true → clavier ouvert immédiatement
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
                        text     = "No messages yet.\nBe the first to say something!",
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
