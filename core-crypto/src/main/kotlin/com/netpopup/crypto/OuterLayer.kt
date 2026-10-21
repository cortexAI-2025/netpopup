package com.netpopup.crypto

import java.security.GeneralSecurityException

/**
 * Couche extérieure du payload d'une enveloppe : chiffre avec `envKey` (dérivée du
 * secret de la conversation) en liant l'AAD de l'en-tête. Un relais qui modifie
 * l'en-tête (destTag, dates, ack…) invalide donc l'étiquette d'authentification.
 *
 * Format : `nonce(24) ‖ chiffré ‖ étiquette(16)` — 40 octets de surcoût, soit le
 * minimum de payload imposé par `EnvelopeCodec`.
 */
object OuterLayer {
    const val OVERHEAD = XChaCha20Poly1305.NONCE_SIZE + XChaCha20Poly1305.TAG_SIZE

    fun seal(envKey: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = XChaCha20Poly1305.newNonce()
        return nonce + XChaCha20Poly1305.encrypt(envKey, nonce, aad, plaintext)
    }

    /** @return le clair, ou null si le payload n'est pas pour cette clé ou a été altéré. */
    fun open(envKey: ByteArray, aad: ByteArray, payload: ByteArray): ByteArray? {
        if (payload.size < OVERHEAD) return null
        val nonce = payload.copyOfRange(0, XChaCha20Poly1305.NONCE_SIZE)
        val body = payload.copyOfRange(XChaCha20Poly1305.NONCE_SIZE, payload.size)
        return try {
            XChaCha20Poly1305.decrypt(envKey, nonce, aad, body)
        } catch (_: GeneralSecurityException) {
            null
        }
    }
}
