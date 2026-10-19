package com.netpopup.dtn.envelope

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/** Raison du refus d'une trame reçue. */
enum class RejectReason {
    TOO_SHORT,
    TOO_LARGE,
    UNKNOWN_VERSION,
    UNKNOWN_KIND,
    BAD_LIFETIME,
    BAD_TIMESTAMP,
    ID_MISMATCH,
    FROM_THE_FUTURE,
    EXPIRED
}

sealed interface DecodeResult {
    data class Accepted(val envelope: Envelope) : DecodeResult
    data class Rejected(val reason: RejectReason) : DecodeResult
}

/**
 * Sérialisation et validation des enveloppes.
 *
 * [decode] traite toute trame comme hostile : il ne lève jamais d'exception,
 * n'alloue rien au-delà de [Envelope.MAX_SIZE], et renvoie la première raison
 * de refus rencontrée. `hopCount` et `hopLimit` ne sont jamais des motifs de refus.
 */
object EnvelopeCodec {

    fun encode(envelope: Envelope): ByteArray {
        val payload = envelope.payload
        return ByteBuffer.allocate(Envelope.HEADER_SIZE + payload.size)
            .put(Envelope.VERSION)
            .put(envelope.kind.code)
            .put(envelope.idBytes())
            .put(envelope.destTag)
            .putLong(envelope.createdAt)
            .putInt(envelope.lifetime)
            .put(envelope.hopLimit.toByte())
            .put(envelope.hopCount.toByte())
            .put(envelope.ack)
            .put(payload)
            .array()
    }

    /**
     * Décode et valide une trame selon l'horloge locale [nowSeconds] :
     *  - taille dans [[Envelope.HEADER_SIZE] + [Envelope.MIN_PAYLOAD_SIZE], [Envelope.MAX_SIZE]] ;
     *  - version et type connus, `lifetime` dans ]0, 24 h] ;
     *  - `createdAt` au plus 2 h dans le futur ;
     *  - non expirée : `createdAt + lifetime + 2 h ≥ maintenant` ;
     *  - `envelopeId` égal au hachage recalculé.
     */
    fun decode(frame: ByteArray, nowSeconds: Long): DecodeResult {
        if (frame.size < Envelope.HEADER_SIZE + Envelope.MIN_PAYLOAD_SIZE) return reject(RejectReason.TOO_SHORT)
        if (frame.size > Envelope.MAX_SIZE) return reject(RejectReason.TOO_LARGE)

        return try {
            val buf = ByteBuffer.wrap(frame)
            if (buf.get() != Envelope.VERSION) return reject(RejectReason.UNKNOWN_VERSION)
            val kind = EnvelopeKind.fromCode(buf.get()) ?: return reject(RejectReason.UNKNOWN_KIND)
            val claimedId = ByteArray(Envelope.FIELD_SIZE).also { buf.get(it) }
            val destTag = ByteArray(Envelope.FIELD_SIZE).also { buf.get(it) }
            val createdAt = buf.getLong()
            val lifetime = buf.getInt()
            val hopLimit = buf.get().toInt() and 0xFF
            val hopCount = buf.get().toInt() and 0xFF
            val ack = ByteArray(Envelope.FIELD_SIZE).also { buf.get(it) }
            val payload = ByteArray(buf.remaining()).also { buf.get(it) }

            if (lifetime !in 1..Envelope.MAX_LIFETIME_SECONDS) return reject(RejectReason.BAD_LIFETIME)
            if (createdAt < 0) return reject(RejectReason.BAD_TIMESTAMP)
            if (createdAt > nowSeconds + Envelope.CLOCK_SKEW_SECONDS) return reject(RejectReason.FROM_THE_FUTURE)

            val envelope = Envelope(kind, destTag, createdAt, lifetime, hopLimit, hopCount, ack, payload)
            if (envelope.isExpired(nowSeconds)) return reject(RejectReason.EXPIRED)
            if (envelope.id.hex != claimedId.toHex()) return reject(RejectReason.ID_MISMATCH)

            DecodeResult.Accepted(envelope)
        } catch (e: BufferUnderflowException) {
            reject(RejectReason.TOO_SHORT)
        }
    }

    private fun reject(reason: RejectReason) = DecodeResult.Rejected(reason)
}
