package com.netpopup.transport.internet

import com.netpopup.dtn.envelope.Envelope

/**
 * Une enveloppe telle que le relais la garde : des octets opaques rangés sous leur étiquette de destination.
 * Le relais ne reçoit **rien d'autre** : ni identité, ni pseudo, ni conversation, ni appareil.
 */
class RelayDocument(
    /** Identifiant de l'enveloppe (16 octets aléatoires, voir [Envelope.id]). */
    val id: ByteArray,
    /** Étiquette de destination de l'heure de création (16 octets) : la seule clé de lecture. */
    val destTag: ByteArray,
    /** Fin de vie (secondes depuis l'epoch) : le relais purge à cette date (TTL). */
    val expiresAtSeconds: Long,
    /** L'enveloppe sérialisée, déjà chiffrée de bout en bout. */
    val envelope: ByteArray,
) {
    init {
        require(id.size == Envelope.FIELD_SIZE) { "id must be ${Envelope.FIELD_SIZE} bytes" }
        require(destTag.size == Envelope.FIELD_SIZE) { "destTag must be ${Envelope.FIELD_SIZE} bytes" }
        require(envelope.size in Envelope.HEADER_SIZE..Envelope.MAX_SIZE) { "envelope size out of bounds" }
    }

    val idHex: String get() = id.toHex()
    val tagHex: String get() = destTag.toHex()
}

enum class PublishResult {
    /** Rangée sur le relais. */
    CREATED,

    /** Le relais l'avait déjà : succès, rien à refaire. */
    EXISTS,

    /** Refusée (règles du relais : taille, durée de vie, format). Inutile de réessayer telle quelle. */
    REJECTED,

    /** Réseau, authentification ou quota : à retenter plus tard. */
    FAILED,
}

/** Résultat d'une relève : [docs] vaut `null` quand le relais n'a pas répondu (distinct de « rien trouvé »). */
class FetchResult(val docs: List<RelayDocument>?) {
    val ok: Boolean get() = docs != null
}

/**
 * Le magasin distant, vu du téléphone. Deux opérations seulement, sans état : ranger une enveloppe, relever
 * celles d'une étiquette. Aucune liste de toutes les étiquettes, aucune recherche : qui ne connaît pas l'étiquette
 * ne peut rien relever.
 */
interface RelayBackend {
    suspend fun publish(doc: RelayDocument): PublishResult

    /** Les enveloppes non expirées rangées sous [destTag], au plus [limit]. */
    suspend fun fetch(destTag: ByteArray, nowSeconds: Long, limit: Int): FetchResult
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    return try { ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() } } catch (e: NumberFormatException) { null }
}
