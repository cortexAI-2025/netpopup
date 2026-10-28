package com.netpopup.diagnostics

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

/** Affiche la trace du dernier plantage, copiable, jusqu'à ce que l'utilisateur la ferme. */
@Composable
fun CrashReportDialog(text: String, onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("The app closed unexpectedly") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Technical details, kept on this phone only. Copy them to report the problem.")
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("NetPopUp crash", text))
            }) { Text("Copy") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
