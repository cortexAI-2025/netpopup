package com.netpopup.dtn.chat

/**
 * Règles de contenu partagées par toutes les implémentations de [ChatRepository].
 */
object MessageRules {

    /** Longueur maximale d'un texte, en caractères. */
    const val MAX_BODY_LENGTH = 500

    /** Durée de vie par défaut d'un message : 6 h. */
    const val DEFAULT_LIFETIME_MS = 6L * 60 * 60 * 1000

    /** Durée de vie maximale d'un message : 24 h. */
    const val MAX_LIFETIME_MS = 24L * 60 * 60 * 1000

    /** Plafond de messages en clair gardés par room. */
    const val MAX_MESSAGES_PER_ROOM = 1_000

    const val ROOM_CODE_LENGTH = 6

    /** Sans O/0 ni I/1, pour éviter les confusions à la lecture. */
    const val ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /**
     * Retourne le texte nettoyé (espaces de bord retirés), prêt à chiffrer.
     *
     * @throws InvalidMessageException si le texte est vide ou dépasse [MAX_BODY_LENGTH].
     */
    fun normalizeBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) throw InvalidMessageException("Message is empty")
        if (trimmed.length > MAX_BODY_LENGTH) {
            throw InvalidMessageException("Message exceeds $MAX_BODY_LENGTH characters")
        }
        return trimmed
    }

    /**
     * Retourne le code en majuscules, sans espaces ni tirets.
     *
     * @throws InvalidRoomCodeException si le code n'a pas [ROOM_CODE_LENGTH] caractères
     *         de [ROOM_CODE_ALPHABET].
     */
    fun normalizeRoomCode(code: String): String {
        val normalized = code.uppercase().filterNot { it.isWhitespace() || it == '-' }
        if (normalized.length != ROOM_CODE_LENGTH || normalized.any { it !in ROOM_CODE_ALPHABET }) {
            throw InvalidRoomCodeException("Room code must be $ROOM_CODE_LENGTH characters from $ROOM_CODE_ALPHABET")
        }
        return normalized
    }
}
