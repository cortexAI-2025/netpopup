package com.netpopup.dtn.envelope

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Type d'enveloppe, porté par l'octet `kind` de l'en-tête. */
enum class EnvelopeKind(val code: Byte) {
    DATA(0x01),
    ACK(0x02),
    ANNOUNCE(0x03);

    companion object {
        fun fromCode(code: Byte): EnvelopeKind? = entries.firstOrNull { it.code == code }
    }
}

/** Identifiant de déduplication : 16 octets, manipulé en hexadécimal. */
@JvmInline
value class EnvelopeId(val hex: String) {
    override fun toString(): String = hex
}

/**
 * Enveloppe DTN (architecture mesh v0.1, section 2).
 *
 * En-tête binaire fixe de [Envelope.HEADER_SIZE] octets, big-endian :
 *
 * | Champ        | Taille | Authentifié (AAD) |
 * |--------------|--------|-------------------|
 * | `version`    | 1      | oui               |
 * | `kind`       | 1      | oui               |
 * | `envelopeId` | 16     | dérivé            |
 * | `destTag`    | 16     | oui               |
 * | `createdAt`  | 8      | oui (secondes)    |
 * | `lifetime`   | 4      | oui (secondes)    |
 * | `hopLimit`   | 1      | oui               |
 * | `hopCount`   | 1      | **non** (mutable) |
 * | `ack`        | 16     | oui               |
 *
 * suivi du payload chiffré (nonce 24 o + texte chiffré + tag 16 o), opaque pour
 * ce module. `envelopeId` n'est pas stocké : il est toujours recalculé, si bien
 * qu'un ID falsifié ne peut pas exister en mémoire ([EnvelopeCodec] le rejette).
 *
 * `hopLimit` et `hopCount` sont indicatifs en v0 : jamais motifs de rejet ni de
 * suppression, car `hopCount` n'est pas authentifié.
 */
class Envelope(
    val kind: EnvelopeKind,
    destTag: ByteArray,
    /** Création selon l'horloge de l'émetteur, en secondes Unix. */
    val createdAt: Long,
    /** Durée de vie en secondes, dans ]0, [MAX_LIFETIME_SECONDS]]. */
    val lifetime: Int,
    val hopLimit: Int = DEFAULT_HOP_LIMIT,
    val hopCount: Int = 0,
    ack: ByteArray = ByteArray(FIELD_SIZE),
    payload: ByteArray
) {
    // Copies défensives : l'enveloppe est immuable une fois construite.
    private val destTagBytes = destTag.copyOf()
    private val ackBytes = ack.copyOf()
    private val payloadBytes = payload.copyOf()

    init {
        require(destTag.size == FIELD_SIZE) { "destTag must be $FIELD_SIZE bytes" }
        require(ack.size == FIELD_SIZE) { "ack must be $FIELD_SIZE bytes" }
        require(createdAt >= 0) { "createdAt must be >= 0" }
        require(lifetime in 1..MAX_LIFETIME_SECONDS) { "lifetime must be in 1..$MAX_LIFETIME_SECONDS" }
        require(hopLimit in 0..0xFF && hopCount in 0..0xFF) { "hop fields are single unsigned bytes" }
        require(payload.size >= MIN_PAYLOAD_SIZE) { "payload must hold at least nonce + tag ($MIN_PAYLOAD_SIZE bytes)" }
        require(HEADER_SIZE + payload.size <= MAX_SIZE) { "envelope exceeds $MAX_SIZE bytes" }
    }

    val destTag: ByteArray get() = destTagBytes.copyOf()
    val ack: ByteArray get() = ackBytes.copyOf()
    val payload: ByteArray get() = payloadBytes.copyOf()

    /** Taille sur le fil. */
    val size: Int get() = HEADER_SIZE + payloadBytes.size

    /**
     * Données associées de l'AEAD : l'en-tête sans `hopCount` ni `envelopeId`
     * ([AAD_SIZE] octets). Toute modification de ces champs rend le payload indéchiffrable.
     */
    fun aad(): ByteArray = ByteBuffer.allocate(AAD_SIZE)
        .put(VERSION)
        .put(kind.code)
        .put(destTagBytes)
        .putLong(createdAt)
        .putInt(lifetime)
        .put(hopLimit.toByte())
        .put(ackBytes)
        .array()

    private val idBytes: ByteArray by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(aad())
        digest.update(payloadBytes)
        digest.digest().copyOf(FIELD_SIZE)
    }

    /** SHA-256(AAD ‖ payload) tronqué à 16 octets : change si un octet immuable change. */
    val id: EnvelopeId by lazy { EnvelopeId(idBytes.toHex()) }

    /** [id] en octets, tel qu'écrit dans l'en-tête. */
    fun idBytes(): ByteArray = idBytes.copyOf()

    /** Instant (secondes) à partir duquel l'enveloppe est expirée, marge de dérive comprise. */
    val expiresAt: Long get() = createdAt + lifetime + CLOCK_SKEW_SECONDS

    fun isExpired(nowSeconds: Long): Boolean = expiresAt < nowSeconds

    /** Copie pour l'émission vers un pair : `hopCount` + 1, saturé à 255. Même [id]. */
    fun forRelay(): Envelope = Envelope(
        kind, destTagBytes, createdAt, lifetime, hopLimit,
        hopCount = (hopCount + 1).coerceAtMost(0xFF), ack = ackBytes, payload = payloadBytes
    )

    /** Égalité des octets sur le fil : même [id] (champs immuables) et même `hopCount`. */
    override fun equals(other: Any?): Boolean =
        other is Envelope && other.id == id && other.hopCount == hopCount

    override fun hashCode(): Int = 31 * id.hashCode() + hopCount

    override fun toString(): String =
        "Envelope(id=$id, kind=$kind, createdAt=$createdAt, lifetime=$lifetime, hops=$hopCount/$hopLimit, size=$size)"

    companion object {
        const val VERSION: Byte = 0x01
        const val HEADER_SIZE = 64
        const val AAD_SIZE = 47
        const val FIELD_SIZE = 16

        /** Enveloppe complète, en-tête compris. */
        const val MAX_SIZE = 4096

        /** Nonce XChaCha20 (24) + tag Poly1305 (16). */
        const val MIN_PAYLOAD_SIZE = 24 + 16

        const val DEFAULT_LIFETIME_SECONDS = 6 * 3600
        const val MAX_LIFETIME_SECONDS = 24 * 3600
        const val DEFAULT_HOP_LIMIT = 8

        /** Dérive d'horloge tolérée entre deux téléphones : ±2 h. */
        const val CLOCK_SKEW_SECONDS = 2L * 3600
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
