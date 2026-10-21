package com.netpopup.dtn.envelope

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Étiquettes de destination tournantes (architecture mesh v0.1, section 2).
 *
 * `destTag = HMAC-SHA256(tagKey, époque)` tronqué à 16 octets, l'époque étant
 * l'heure entière de `createdAt` (8 octets big-endian, signés). Un relais qui ne
 * connaît pas `tagKey` ne peut ni deviner la conversation ni relier deux heures.
 *
 * Le destinataire recalcule l'étiquette à partir du `createdAt` de l'enveloppe,
 * présent en clair et authentifié dans l'en-tête, et non à partir de sa propre
 * horloge : une enveloppe retardée de plusieurs heures par le réseau DTN reste
 * reconnue tant qu'elle n'a pas expiré. La dérive d'horloge entre émetteur et
 * destinataire (±2 h) ne joue que sur la validation ([EnvelopeCodec]), pas ici.
 */
object DestTags {

    const val EPOCH_SECONDS = 3600L

    /** Taille minimale de la clé d'étiquette. */
    const val MIN_KEY_SIZE = 16

    fun epochOf(seconds: Long): Long = Math.floorDiv(seconds, EPOCH_SECONDS)

    fun tag(tagKey: ByteArray, epoch: Long): ByteArray {
        require(tagKey.size >= MIN_KEY_SIZE) { "tag key must be at least $MIN_KEY_SIZE bytes" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(tagKey, "HmacSHA256"))
        return mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(epoch).array())
            .copyOf(Envelope.FIELD_SIZE)
    }

    /** Étiquette à poser sur une enveloppe créée à [createdAtSeconds]. */
    fun forSending(tagKey: ByteArray, createdAtSeconds: Long): ByteArray =
        tag(tagKey, epochOf(createdAtSeconds))

    /**
     * Vrai si [envelope] est destinée à la conversation de [tagKey] : son étiquette
     * est celle de l'époque de son propre `createdAt`. Comparaison à temps constant.
     */
    fun matches(tagKey: ByteArray, envelope: Envelope): Boolean =
        MessageDigest.isEqual(forSending(tagKey, envelope.createdAt), envelope.destTag)
}
