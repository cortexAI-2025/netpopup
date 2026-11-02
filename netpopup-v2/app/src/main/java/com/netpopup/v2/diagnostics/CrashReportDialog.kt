package com.netpopup.v2.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/** Affiche la trace du dernier plantage, copiable, jusqu'à ce que l'utilisateur la ferme. Rien n'est envoyé. */
@Composable
fun CrashReportDialog(text: String, onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text("L'application s'est fermée") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Détails techniques, gardés sur ce téléphone seulement. Appuyez sur « Copier » puis envoyez-les pour signaler le problème.")
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("NetPopUp crash", text))
            }) { Text("Copier") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Fermer et continuer") } },
    )
}
