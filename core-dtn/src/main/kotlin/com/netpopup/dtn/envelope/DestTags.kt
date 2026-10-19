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
 * Le destinataire accepte les époques `e_local − 2 … e_local + 2`, ce qui couvre
 * une dérive d'horloge de ±2 h entre l'émetteur et lui.
 */
object DestTags {

    const val EPOCH_SECONDS = 3600L

    /** Nombre d'époques acceptées de part et d'autre de l'époque locale. */
    const val WINDOW_EPOCHS = 2

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

    /** Les 2 × [WINDOW_EPOCHS] + 1 étiquettes que le destinataire reconnaît à [nowSeconds]. */
    fun acceptedTags(tagKey: ByteArray, nowSeconds: Long): List<ByteArray> {
        val current = epochOf(nowSeconds)
        return (current - WINDOW_EPOCHS..current + WINDOW_EPOCHS).map { tag(tagKey, it) }
    }

    /** Vrai si [destTag] appartient à la conversation de [tagKey] dans la fenêtre. Comparaison à temps constant. */
    fun matches(tagKey: ByteArray, destTag: ByteArray, nowSeconds: Long): Boolean =
        acceptedTags(tagKey, nowSeconds).any { MessageDigest.isEqual(it, destTag) }
}
