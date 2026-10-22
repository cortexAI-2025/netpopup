package com.netpopup.dtn.presentation

import com.netpopup.dtn.chat.ConversationKind
import com.netpopup.dtn.chat.DeliveryState

/** Libellé de l'état d'un message, ou null s'il n'y a rien à afficher. */
object DeliveryDisplay {
    const val QUEUED = "Queued"
    const val RELAYED = "Relayed"
    const val ACKNOWLEDGED = "Delivered"

    /**
     * Seuls nos propres messages ont un état. « Delivered » n'existe qu'en 1:1 : ni les rooms ni la
     * room locale n'ont d'accusé de réception, un message y reste « Relayed » au mieux.
     */
    fun label(state: DeliveryState, kind: ConversationKind?, isOwn: Boolean): String? = when {
        !isOwn -> null
        state == DeliveryState.QUEUED -> QUEUED
        state == DeliveryState.RELAYED -> RELAYED
        state == DeliveryState.ACKNOWLEDGED -> if (kind == ConversationKind.DIRECT) ACKNOWLEDGED else RELAYED
        else -> null
    }
}
