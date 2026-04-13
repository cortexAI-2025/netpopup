package com.netpopup.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.netpopup.data.model.Message.Companion.MAX_LENGTH
import com.netpopup.ui.theme.Outline
import com.netpopup.ui.theme.Primary
import com.netpopup.ui.theme.Surface
import com.netpopup.ui.theme.SurfaceVar
import com.netpopup.ui.theme.TextSecondary
import com.netpopup.ui.util.FeedbackManager
import kotlinx.coroutines.delay

/**
 * Barre de saisie fixe en bas de l'écran.
 *
 * Responsabilités :
 *  - Saisie texte (≤ MAX_LENGTH), multi-ligne (max 4 lignes).
 *  - Auto-focus clavier si [autoFocus] = true.
 *  - Délègue tout le feedback sensoriel à [feedbackManager] — pas de
 *    référence directe à HapticFeedback ici.
 */
@Composable
fun ChatInputBar(
    onSend: (String) -> Unit,
    feedbackManager: FeedbackManager,
    autoFocus: Boolean = true,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // ── Auto-focus ────────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        if (autoFocus) {
            delay(150) // laisse le Scaffold se stabiliser avant requestFocus
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    val canSend = text.isNotBlank()

    fun doSend() {
        val trimmed = text.trim()
        if (trimmed.isNotBlank()) {
            feedbackManager.onSent()   // feedback envoi via FeedbackManager
            onSend(trimmed)
            text = ""
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .imePadding(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value           = text,
            onValueChange   = { if (it.length <= MAX_LENGTH) text = it },
            placeholder     = { Text("Message…", color = TextSecondary) },
            singleLine      = false,
            maxLines        = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { doSend() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor   = SurfaceVar,
                unfocusedContainerColor = SurfaceVar,
                focusedIndicatorColor   = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor             = Primary
            ),
            shape    = RoundedCornerShape(24.dp),
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
                .focusRequester(focusRequester)
        )

        IconButton(
            onClick  = { doSend() },
            enabled  = canSend,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (canSend) Primary else Outline)
        ) {
            Icon(
                imageVector        = Icons.Filled.Send,
                contentDescription = "Send",
                tint               = if (canSend) Color.Black else TextSecondary
            )
        }
    }
}
