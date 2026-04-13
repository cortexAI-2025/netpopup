package com.netpopup.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Centralise tout le feedback sensoriel de l'application.
 *
 * Avantages d'avoir une seule classe :
 *  - Les types de vibration sont nommés par intention (sent, received…)
 *    et non par constante technique (LongPress, TextHandleMove).
 *  - Ajouter audio, LED ou toute autre couche de feedback ne touche
 *    qu'à cette classe — les screens n'en savent rien.
 *  - Testable : il suffit de mocker [FeedbackManager] dans les tests.
 *
 * Points d'extension prévus (commentés) :
 *   onReaction(), onMention(), onError()
 */
@Stable
class FeedbackManager(private val haptic: HapticFeedback) {

    /**
     * Confirmation forte — action explicitement déclenchée par l'utilisateur.
     * Ex : appui sur Send.
     * → LongPress  (bref mais perceptible, similaire clavier physique)
     */
    fun onSent() = haptic.performHapticFeedback(HapticFeedbackType.LongPress)

    /**
     * Signal passif — événement entrant, non sollicité.
     * Ex : nouveau message d'un autre utilisateur.
     * → TextHandleMove  (très léger, non-intrusif)
     */
    fun onReceived() = haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    // ── Extension points ──────────────────────────────────────────────────────
    // fun onReaction()  = haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    // fun onMention()   = haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    // fun onError()     = haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    // fun onRoomJoined()= haptic.performHapticFeedback(HapticFeedbackType.LongPress)
}

/** Crée et mémorise un [FeedbackManager] lié au [HapticFeedback] local. */
@Composable
fun rememberFeedbackManager(): FeedbackManager {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { FeedbackManager(haptic) }
}
