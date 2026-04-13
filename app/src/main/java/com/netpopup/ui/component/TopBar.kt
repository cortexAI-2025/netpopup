package com.netpopup.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netpopup.R
import com.netpopup.ui.theme.Background
import com.netpopup.ui.theme.Primary
import com.netpopup.ui.theme.TextPrimary
import com.netpopup.ui.theme.TextSecondary

/**
 * Barre de titre unifiée.
 *
 * [showBrandLogo] = true → affiche le **logo secondaire** (bulle + "NetPopUp"
 * en deux couleurs), identique au branding officiel.
 * [showBrandLogo] = false → affiche [title] + [subtitle] en texte.
 *
 * Usage :
 *   - Écran principal Local Chat : showBrandLogo = true
 *   - Écrans internes (Private Chat, Rooms…) : texte standard avec [onBack]
 */
@Composable
fun NetPopUpTopBar(
    title: String = "",
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    showBrandLogo: Boolean = false,
    actions: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Background)
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Bouton retour ─────────────────────────────────────────────────────
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint               = TextPrimary
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // ── Centre : logo secondaire OU titre texte ───────────────────────────
        if (showBrandLogo) {
            SecondaryLogo()
        } else {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text       = title,
                    color      = TextPrimary,
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (subtitle != null) {
                    Text(
                        text  = subtitle,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        actions()
    }
}

/**
 * Logo secondaire : icône bulle + "Net**PopUp**" bicolore.
 *
 * Correspond au "LOGO SECONDAIRE — Pour headers et usages horizontaux"
 * du guide de marque.
 */
@Composable
private fun SecondaryLogo() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Icône bulle — tint=Unspecified préserve les couleurs natives du SVG
        Icon(
            painter            = painterResource(id = R.drawable.ic_bubble),
            contentDescription = "NetPopUp logo",
            tint               = Color.Unspecified,
            modifier           = Modifier.size(28.dp)
        )

        Spacer(Modifier.width(7.dp))

        // "Net" blanc + "PopUp" vert néon, tout en bold
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Black)) {
                    append("Net")
                }
                withStyle(SpanStyle(color = Primary, fontWeight = FontWeight.Black)) {
                    append("PopUp")
                }
            },
            fontSize = 20.sp,
            letterSpacing = (-0.5).sp
        )
    }
}
