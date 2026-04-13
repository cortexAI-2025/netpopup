package com.netpopup.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * NetPopUp enforces dark-only mode — it is intentional and not a limitation.
 */
private val DarkColorScheme = darkColorScheme(
    primary          = Primary,
    onPrimary        = OnPrimary,
    primaryContainer = PrimaryVar,
    secondary        = Secondary,
    onSecondary      = OnSecondary,
    background       = Background,
    surface          = Surface,
    surfaceVariant   = SurfaceVar,
    onBackground     = TextPrimary,
    onSurface        = TextPrimary,
    outline          = Outline,
    error            = Error,
    onError          = OnError
)

@Composable
fun NetPopUpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = NetPopUpTypography,
        content     = content
    )
}
