package com.netpopup.dtn.envelope

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Jetons d'acquittement 1:1 (architecture mesh v0.1, sections 2 et 3).
 *
 * L'émetteur met un `ackToken` aléatoire dans le payload chiffré et son hachage
 * dans le champ `ack` de l'en-tête. Le destinataire, seul à connaître le jeton,
 * le publie en clair dans le champ `ack` d'une enveloppe `ACK`.
 */
object AckTokens {

    private val random = SecureRandom()

    fun newToken(): ByteArray = ByteArray(Envelope.FIELD_SIZE).also(random::nextBytes)

    /** SHA-256(jeton) tronqué à 16 octets : valeur du champ `ack` d'une enveloppe `DATA`. */
    fun hash(token: ByteArray): ByteArray {
        require(token.size == Envelope.FIELD_SIZE) { "ack token must be ${Envelope.FIELD_SIZE} bytes" }
        return MessageDigest.getInstance("SHA-256").digest(token).copyOf(Envelope.FIELD_SIZE)
    }
}
