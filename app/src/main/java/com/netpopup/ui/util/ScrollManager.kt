package com.netpopup.ui.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Encapsule toute la logique de scroll pour un LazyColumn de messages.
 *
 * Seuil pixel-based (pas item-based) : robuste quelle que soit la densité
 * d'écran et la hauteur variable des bulles.
 *
 * Deux comportements distincts (à appeler depuis le screen) :
 *   [shouldScrollForSelf]     → toujours vrai  (propre message)
 *   [shouldScrollForIncoming] → conditionnel   (message reçu)
 */
@Stable
class ScrollManager(
    val listState: LazyListState,
    private val thresholdPx: Int
) {
    /**
     * Vrai si l'ITEM FINAL est visible ET que le bas de cet item se situe
     * à moins de [thresholdPx] pixels du bas du viewport.
     *
     * Utilise derivedStateOf pour ne recalculer que quand layoutInfo change,
     * sans déclencher de recomposition inutile.
     */
    private val _isAtBottom = derivedStateOf {
        val info        = listState.layoutInfo
        val totalItems  = info.totalItemsCount
        if (totalItems == 0) return@derivedStateOf true   // liste vide → "en bas"

        val lastVisible = info.visibleItemsInfo.lastOrNull()
            ?: return@derivedStateOf false

        // L'item visible doit être le dernier de la liste
        if (lastVisible.index < totalItems - 1) return@derivedStateOf false

        // Distance en pixels entre le bas de l'item et le bas du viewport
        val distanceToBottom = info.viewportEndOffset - (lastVisible.offset + lastVisible.size)
        distanceToBottom <= thresholdPx
    }

    /** Lecture snapshot-safe depuis n'importe quel composable. */
    val isAtBottom: Boolean get() = _isAtBottom.value

    // ── Règles de scroll ──────────────────────────────────────────────────────

    /** Propre message → scroll systématique, sans condition. */
    val shouldScrollForSelf: Boolean get() = true

    /** Message entrant → scroll uniquement si l'utilisateur est déjà en bas. */
    val shouldScrollForIncoming: Boolean get() = isAtBottom

    // ── Action ───────────────────────────────────────────────────────────────

    /** Descend au dernier item avec animation fluide. */
    suspend fun scrollToLatest(itemCount: Int) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
}

/**
 * Fabrique un [ScrollManager] lié à un [LazyListState] persistant.
 *
 * [threshold] : distance max en dp depuis le bas → « utilisateur est en bas ».
 * 200 dp est raisonnable : ≈ 2–3 bulles selon la densité.
 */
@Composable
fun rememberScrollManager(threshold: Dp = 200.dp): ScrollManager {
    val listState   = rememberLazyListState()
    val thresholdPx = with(LocalDensity.current) { threshold.roundToPx() }
    return remember(listState, thresholdPx) { ScrollManager(listState, thresholdPx) }
}
