package com.netpopup.ui.theme

import androidx.compose.ui.graphics.Color

// ── NetPopUp brand palette — neon green dark theme ────────────────────────────
// Couleur principale tirée du logo officiel : vert néon #39FF14

val Background    = Color(0xFF0A0A0A)   // canvas quasi-noir
val Surface       = Color(0xFF141414)   // fond des cartes / input
val SurfaceVar    = Color(0xFF1E1E1E)   // surface légèrement plus claire

/** Vert néon brand — identique au logo principal */
val Primary       = Color(0xFF39FF14)
/** Vert plus sombre — états pressed / containers */
val PrimaryVar    = Color(0xFF2CC10F)
val OnPrimary     = Color(0xFF000000)   // texte noir sur fond vert

val Secondary     = Color(0xFF7CFC4A)   // vert secondaire, moins saturé
val OnSecondary   = Color(0xFF000000)

val Outline       = Color(0xFF2C2C2C)   // bordures subtiles

// Bulles de messages
/** Propre message — vert très sombre pour rester lisible sur fond noir */
val BubbleSelf    = Color(0xFF0A2E06)
/** Message d'autrui */
val BubbleOther   = Color(0xFF1E1E1E)

val TextPrimary   = Color(0xFFEEEEEE)
val TextSecondary = Color(0xFF888888)   // timestamps, labels de zone

val Error         = Color(0xFFFF5252)
val OnError       = Color(0xFFFFFFFF)
