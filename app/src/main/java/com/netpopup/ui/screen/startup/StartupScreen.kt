package com.netpopup.ui.screen.startup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

/** Do not open chat collectors until native storage/crypto initialization has succeeded. */
@Composable
fun StartupScreen(prepare: suspend () -> Unit, onReady: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(attempt) {
        failure = null
        try {
            prepare()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
        } catch (e: LinkageError) {
            failure = e
        }
        if (failure == null) onReady()
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("NetPopUp", style = MaterialTheme.typography.headlineMedium)
        val error = failure
        if (error == null) {
            CircularProgressIndicator()
            Text("Opening encrypted storage…")
        } else {
            Text("Unable to open NetPopUp", style = MaterialTheme.typography.titleMedium)
            Text("Your local data has been kept. Retry, or copy the diagnostic to report the problem.")
            Text(error.javaClass.simpleName)
            Button(onClick = { attempt++ }) { Text("Retry") }
            TextButton(onClick = { clipboard.setText(AnnotatedString(error.stackTraceToString().take(12_000))) }) {
                Text("Copy diagnostic")
            }
        }
    }
}
