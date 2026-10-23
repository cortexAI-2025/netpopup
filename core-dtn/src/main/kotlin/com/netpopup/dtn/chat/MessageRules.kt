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

    const val ROOM_LINK_PREFIX = "netpopup://room/"
    const val CONTACT_LINK_PREFIX = "netpopup://contact/"

    /** Une invitation de room encode 68 octets ; un lien plus court est forcément tronqué. */
    const val MIN_ROOM_LINK_BODY = 91
    const val MAX_ROOM_LINK_LENGTH = 512

    /** Un bundle avec pré-clé Kyber fait environ 1,8 Ko, soit ~2,4 Ko en base64. */
    const val MIN_CONTACT_LINK_BODY = 100
    const val MAX_CONTACT_LINK_LENGTH = 3_000

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

    private val base64Url = Regex("^[A-Za-z0-9_-]+$")

    /**
     * Vérifie la forme d'un lien d'invitation de room (préfixe, alphabet, longueur) et le renvoie nettoyé.
     * La validation complète — version, clés, politique — est faite par le moteur au décodage.
     *
     * @throws InvalidInviteException si le lien n'a pas la forme d'une invitation.
     */
    fun normalizeInviteLink(link: String): String =
        normalizeLink(link, ROOM_LINK_PREFIX, MIN_ROOM_LINK_BODY, MAX_ROOM_LINK_LENGTH, "room invitation")

    /** Même vérification de forme pour un lien de contact. */
    fun normalizeContactLink(link: String): String =
        normalizeLink(link, CONTACT_LINK_PREFIX, MIN_CONTACT_LINK_BODY, MAX_CONTACT_LINK_LENGTH, "contact")

    private fun normalizeLink(link: String, prefix: String, minBody: Int, maxLength: Int, what: String): String {
        val trimmed = link.trim()
        if (trimmed.length > maxLength) throw InvalidInviteException("The $what link is too long")
        if (!trimmed.startsWith(prefix)) throw InvalidInviteException("This is not a $what link")
        val body = trimmed.removePrefix(prefix)
        if (body.length < minBody || !base64Url.matches(body)) throw InvalidInviteException("The $what link is damaged")
        return trimmed
    }
}
