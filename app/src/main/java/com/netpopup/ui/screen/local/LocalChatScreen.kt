package com.netpopup.ui.screen.local

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.netpopup.ui.component.ChatInputBar
import com.netpopup.ui.component.MessageBubble
import com.netpopup.ui.component.NetPopUpTopBar
import com.netpopup.ui.theme.Primary

/**
 * Local geo-based chat screen.
 *
 * On first render it requests coarse location permission so the ViewModel can
 * compute the zone ID.  If permission is denied, the app falls back to a
 * "Default Zone" and all users without location share that room.
 */
@Composable
fun LocalChatScreen(
    onNavigateToRooms: () -> Unit,
    viewModel: LocalChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // Request coarse location permission on first composition
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled inside ViewModel via FusedLocationClient */ }

    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    // Scroll to the newest message whenever the list grows
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    // Show errors via Snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        },
        topBar = {
            NetPopUpTopBar(
                title    = "Local Chat",
                subtitle = if (uiState.zoneDisplay.isNotBlank()) "Zone ${uiState.zoneDisplay}" else null,
                actions  = {
                    // Navigate to private rooms
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
