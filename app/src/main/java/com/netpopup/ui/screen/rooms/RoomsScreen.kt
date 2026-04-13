package com.netpopup.ui.screen.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.netpopup.ui.component.NetPopUpTopBar
import com.netpopup.ui.theme.Outline
import com.netpopup.ui.theme.Primary
import com.netpopup.ui.theme.TextPrimary
import com.netpopup.ui.theme.TextSecondary

/**
 * Screen for creating or joining a private room.
 *
 * Layout:
 *  ┌────────────────────────────────┐
 *  │  ← Private Rooms               │
 *  ├────────────────────────────────┤
 *  │  [ Create New Room ]  (button) │
 *  │  ─────────  or  ──────────     │
 *  │  [  Enter room code  ]         │
 *  │  [ Join Room ]       (button)  │
 *  └────────────────────────────────┘
 */
@Composable
fun RoomsScreen(
    onBack: () -> Unit,
    onJoinRoom: (roomId: String) -> Unit,
    viewModel: RoomsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Navigate when a room is created / joined
    LaunchedEffect(uiState.navigateTo) {
        uiState.navigateTo?.let { roomId ->
            viewModel.onNavigated()
            onJoinRoom(roomId)
        }
    }

    // Show errors
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
        topBar = {
            NetPopUpTopBar(title = "Private Rooms", onBack = onBack)
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ── Create section ────────────────────────────────────────────────
            Text(
                text  = "Start a new room",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick  = { viewModel.createRoom() },
                enabled  = !uiState.isLoading,
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Primary),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(
                        "Create New Room",
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            // ── Divider ───────────────────────────────────────────────────────
            Spacer(Modifier.height(32.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(Outline)
                )
                Text(
                    "  or  ",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(Outline)
                )
            }
            Spacer(Modifier.height(32.dp))

            // ── Join section ──────────────────────────────────────────────────
            Text(
                text  = "Join an existing room",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value         = uiState.joinCode,
                onValueChange = viewModel::onJoinCodeChanged,
                placeholder   = { Text("Room code (e.g. XK7B2Q)", color = TextSecondary) },
                singleLine    = true,
                textStyle     = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily   = FontFamily.Monospace,
                    letterSpacing = 4.sp,
                    color        = TextPrimary
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType   = KeyboardType.Text,
                    imeAction      = ImeAction.Done
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = Primary,
                    unfocusedBorderColor = Outline,
                    cursorColor          = Primary
                ),
                shape    = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick  = { viewModel.joinRoom() },
                enabled  = !uiState.isLoading && uiState.joinCode.length >= 6,
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Primary),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(
                    "Join Room",
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}
