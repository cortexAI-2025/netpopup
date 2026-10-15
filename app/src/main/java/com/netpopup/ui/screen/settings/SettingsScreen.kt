package com.netpopup.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.netpopup.ui.component.NetPopUpTopBar
import com.netpopup.ui.theme.OnPrimary
import com.netpopup.ui.theme.Primary
import com.netpopup.ui.theme.SurfaceVar
import com.netpopup.ui.theme.TextPrimary
import com.netpopup.ui.theme.TextSecondary

/**
 * Settings screen.
 *
 *  ┌────────────────────────────────┐
 *  │  ← Settings                    │
 *  ├────────────────────────────────┤
 *  │  Username   Wolf_4821   [New]  │
 *  │  Notifications           (on)  │
 *  │  Blocked users                 │
 *  │  Fox_1234            [Unblock] │
 *  └────────────────────────────────┘
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = { NetPopUpTopBar(title = "Settings", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            // ── Identity ──────────────────────────────────────────────────────
            SectionTitle("Identity")
            SettingsRow {
                Column(Modifier.weight(1f)) {
                    Text(uiState.username, color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "Your anonymous name. New messages use the new one.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = viewModel::regenerateUsername) {
                    Text("New name", color = Primary)
                }
            }

            // ── Notifications ─────────────────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            SectionTitle("Notifications")
            SettingsRow {
                Column(Modifier.weight(1f)) {
                    Text("New messages", color = TextPrimary)
                    Text(
                        "Local zone and private rooms you joined",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked         = uiState.notificationsEnabled,
                    onCheckedChange = viewModel::setNotificationsEnabled,
                    colors          = SwitchDefaults.colors(
                        checkedThumbColor = OnPrimary,
                        checkedTrackColor = Primary
                    )
                )
            }

            // ── Blocked users ─────────────────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            SectionTitle("Blocked users")
            if (uiState.blockedUsers.isEmpty()) {
                Text(
                    "Nobody blocked. Long-press a message to block its author.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                uiState.blockedUsers.forEach { blocked ->
                    SettingsRow {
                        Text(
                            text     = blocked.username.ifBlank { "User ${blocked.id.take(6)}…" },
                            color    = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.unblock(blocked.id) }) {
                            Text("Unblock", color = Primary)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text     = text.uppercase(),
        color    = TextSecondary,
        style    = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun SettingsRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceVar, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
