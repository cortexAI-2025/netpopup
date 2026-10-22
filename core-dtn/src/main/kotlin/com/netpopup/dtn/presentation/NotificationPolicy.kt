package com.netpopup.dtn.presentation

import com.netpopup.dtn.chat.ChatMessage
import com.netpopup.dtn.chat.ConversationId

/**
 * Quand notifier un message reçu, et quoi écrire (mesh-17). La notification est locale et **ne révèle rien** :
 * ni pseudo, ni conversation, ni contenu ; une seule notification remplace les précédentes.
 */
object NotificationPolicy {

    /** Texte fixe : le même quel que soit le message. */
    const val TEXT = "New message"

    fun shouldNotify(message: ChatMessage, enabled: Boolean, blocked: Set<String>, shownConversation: ConversationId?): Boolean =
        enabled && !message.isOwn && message.senderKey !in blocked && message.conversationId != shownConversation
}

/** Durées de vie proposées dans les réglages (mesh-18) : 6 h par défaut, 24 h au maximum. */
object LifetimeChoices {
    val HOURS = listOf(1, 2, 6, 12, 24)
    const val DEFAULT_HOURS = 6

    /** Ramène n'importe quelle valeur stockée au choix valide le plus proche. */
    fun normalize(hours: Int): Int = HOURS.minByOrNull { kotlin.math.abs(it - hours) } ?: DEFAULT_HOURS

    fun toSeconds(hours: Int): Int = normalize(hours) * 3600
}
