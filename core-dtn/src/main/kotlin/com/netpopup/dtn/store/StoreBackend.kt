package com.netpopup.dtn.store

import com.netpopup.dtn.envelope.Envelope
import com.netpopup.dtn.envelope.EnvelopeId

/** Qui a mis l'enveloppe dans le store. */
enum class Origin {
    /** Créée par cet appareil : jamais évincée avant expiration. */
    SELF,

    /** Reçue d'un pair, gardée pour être relayée. */
    RELAY
}

data class StoredEnvelope(
    val envelope: Envelope,
    val origin: Origin,
    /** Arrivée dans ce store, horloge locale, secondes. */
    val storedAt: Long
) {
    val id: EnvelopeId get() = envelope.id
}

/**
 * Stockage brut des enveloppes et des pierres tombales, sans aucune règle.
 *
 * [EnvelopeStore] porte toute la logique ; un backend ne fait que lire et écrire.
 * [InMemoryStoreBackend] sert aux tests et au simulateur ; le backend SQLCipher
 * de l'app arrive avec mesh-13 (#14).
 */
interface StoreBackend {
    fun get(id: EnvelopeId): StoredEnvelope?
    fun put(entry: StoredEnvelope)
    fun remove(id: EnvelopeId)
    fun all(): List<StoredEnvelope>

    fun hasTombstone(id: EnvelopeId): Boolean
    fun putTombstone(id: EnvelopeId, purgeAt: Long)

    /** Retire les pierres tombales dont `purgeAt` est dépassé ; renvoie leur nombre. */
    fun purgeTombstones(nowSeconds: Long): Int
    fun tombstoneCount(): Int
    fun tombstoneIds(): List<EnvelopeId>
}

class InMemoryStoreBackend : StoreBackend {
    private val envelopes = LinkedHashMap<EnvelopeId, StoredEnvelope>()
    private val tombstones = HashMap<EnvelopeId, Long>()

    override fun get(id: EnvelopeId) = envelopes[id]
    override fun put(entry: StoredEnvelope) { envelopes[entry.id] = entry }
    override fun remove(id: EnvelopeId) { envelopes.remove(id) }
    override fun all(): List<StoredEnvelope> = envelopes.values.toList()

    override fun hasTombstone(id: EnvelopeId) = id in tombstones
    override fun putTombstone(id: EnvelopeId, purgeAt: Long) { tombstones[id] = purgeAt }

    override fun purgeTombstones(nowSeconds: Long): Int {
        val expired = tombstones.filterValues { it < nowSeconds }.keys
        expired.forEach(tombstones::remove)
        return expired.size
    }

    override fun tombstoneCount() = tombstones.size
    override fun tombstoneIds(): List<EnvelopeId> = tombstones.keys.toList()
}
