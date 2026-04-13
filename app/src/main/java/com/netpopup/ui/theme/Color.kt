package com.netpopup.ui.theme

import androidx.compose.ui.graphics.Color

// ── NetPopUp dark palette ─────────────────────────────────────────────────────

val Background    = Color(0xFF0A0A0A)   // near-black canvas
val Surface       = Color(0xFF141414)   // card / input background
val SurfaceVar    = Color(0xFF1E1E1E)   // slightly lighter surface

val Primary       = Color(0xFF00E5FF)   // cyan accent — taps & highlights
val PrimaryVar    = Color(0xFF00B8D4)   // darker cyan for pressed state
val OnPrimary     = Color(0xFF000000)

val Secondary     = Color(0xFF80CBC4)   // muted teal
val OnSecondary   = Color(0xFF000000)

val Outline       = Color(0xFF2C2C2C)   // subtle borders

// Message bubbles
val BubbleSelf    = Color(0xFF00344A)   // user's own messages
val BubbleOther   = Color(0xFF1E1E1E)   // other users' messages

val TextPrimary   = Color(0xFFEEEEEE)
val TextSecondary = Color(0xFF888888)   // timestamps, zone labels

val Error         = Color(0xFFFF5252)
val OnError       = Color(0xFFFFFFFF)
