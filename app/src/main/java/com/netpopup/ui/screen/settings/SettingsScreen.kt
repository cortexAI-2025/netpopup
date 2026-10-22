package com.netpopup.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.netpopup.dtn.presentation.LifetimeChoices
import com.netpopup.mesh.MeshPermissions
import com.netpopup.ui.component.NetPopUpTopBar
import com.netpopup.ui.theme.Error
import com.netpopup.ui.theme.Primary
import com.netpopup.ui.theme.TextPrimary
import com.netpopup.ui.theme.TextSecondary

private const val SOURCE_URL = "https://github.com/cortexAI-2025/netpopup"

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val settings = state.settings

    // Le service refuse de démarrer sans permissions : on les demande avant de l'activer.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (MeshPermissions.missing(context).isEmpty()) viewModel.setMeshEnabled(true)
    }
    val missing = MeshPermissions.missing(context)

    Scaffold(
        topBar = { NetPopUpTopBar(title = "Settings", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Mesh ──────────────────────────────────────────────────────────
            item { Header("Mesh") }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mesh service", color = TextPrimary)
                        Text(
                            if (settings.meshEnabled) "On: this phone receives and relays messages."
                            else "Off: no reception and no relaying until you turn it back on.",
                            color = TextSecondary, style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = settings.meshEnabled,
                        onCheckedChange = { on ->
                            if (!on) viewModel.setMeshEnabled(false)
                            else if (missing.isEmpty()) viewModel.setMeshEnabled(true)
                            else permissions.launch(MeshPermissions.toRequest(context).toTypedArray())
                        }
                    )
                }
            }
            item {
                Text(
                    if (state.status.isRunning) "Active · ${state.status.peersInRange} phone(s) in range · ${state.info.storedEnvelopes} envelope(s) stored"
                    else "Stopped · ${state.info.storedEnvelopes} envelope(s) stored",
                    color = TextSecondary, style = MaterialTheme.typography.bodySmall
                )
            }
            if (missing.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Some permissions are missing, so the mesh cannot run.", color = Error, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { permissions.launch(MeshPermissions.toRequest(context).toTypedArray()) }) { Text("Grant permissions") }
                        TextButton(onClick = {
                            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }) { Text("Open Android settings") }
                    }
                }
            }
            if (state.info.clockSkewSuspected) {
                item {
                    Text(
                        "Clock warning: several messages were refused because their date looks wrong. Your phone's clock (or a neighbour's) " +
                            "may be off by more than 2 hours. Check Date & time in Android settings.",
                        color = Error, style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ── Identité ──────────────────────────────────────────────────────
            item { Header("Identity") }
            item { NameField(settings.displayName, viewModel::setDisplayName, viewModel::generateDisplayName) }
            item {
                Column {
                    Text("Your key fingerprint", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Text(state.fingerprint.ifEmpty { "…" }, color = Primary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    Text("Compare it in person with your contact to be sure it is them.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }

            // ── Messages ──────────────────────────────────────────────────────
            item { Header("Messages") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Lifetime of the messages you send", color = TextPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LifetimeChoices.HOURS.forEach { h ->
                            val selected = h == settings.lifetimeHours
                            if (selected) Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("${h}h", color = MaterialTheme.colorScheme.onPrimary) }
                            else OutlinedButton(onClick = { viewModel.setLifetimeHours(h) }) { Text("${h}h") }
                        }
                    }
                    Text("Messages disappear from every phone after this delay (24 h at most).", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }

            // ── Notifications ─────────────────────────────────────────────────
            item { Header("Notifications") }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Notify me of new messages", color = TextPrimary)
                        Text("The notification only says \"New message\": no name, no room, no content.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = settings.notificationsEnabled, onCheckedChange = viewModel::setNotificationsEnabled)
                }
            }

            // ── Blocage ───────────────────────────────────────────────────────
            item { Header("Blocked senders") }
            item {
                Text(
                    "Blocking happens only on this phone. There is no central report and no ban: a blocked sender's messages are hidden " +
                        "here, but your phone still relays them for others.",
                    color = TextSecondary, style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.blocked.isEmpty()) {
                item { Text("Nobody is blocked.", color = TextSecondary) }
            } else {
                items(state.blocked.toList().sorted(), key = { "blocked-$it" }) { key ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(key.take(16) + "…", color = TextPrimary, fontFamily = FontFamily.Monospace)
                        TextButton(onClick = { viewModel.unblock(key) }) { Text("Unblock") }
                    }
                }
            }

            // ── À propos ──────────────────────────────────────────────────────
            item { Header("About") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("NetPopUp is free software under the GNU AGPL-3.0.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) }) { Text("View the source code") }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) =
    Text(text, color = TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))

@Composable
private fun NameField(current: String, onSave: (String) -> Unit, onGenerate: () -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it.take(32) }, label = { Text("Display name") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(text) }, enabled = text.isNotBlank() && text.trim() != current, colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                Text("Save", color = MaterialTheme.colorScheme.onPrimary)
            }
            OutlinedButton(onClick = onGenerate) { Text("Random name") }
        }
    }
}
