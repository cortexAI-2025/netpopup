package com.netpopup.dtn.store

import com.netpopup.dtn.envelope.AckTokens
import com.netpopup.dtn.envelope.Envelope
import com.netpopup.dtn.envelope.EnvelopeId
import com.netpopup.dtn.envelope.EnvelopeKind

/** Plafonds du store (architecture mesh v0.1, section 3), à calibrer au test terrain. */
data class StoreLimits(
    val maxEnvelopes: Int = 5_000,
    val maxBytes: Long = 20L * 1024 * 1024,
    /** Plafond par `destTag` chez un relais. */
    val maxPerDestTag: Int = 1_000
)

sealed interface InsertResult {
    /** Stockée ; [evicted] liste les enveloppes sorties pour lui faire de la place. */
    data class Stored(val evicted: List<EnvelopeId> = emptyList()) : InsertResult
    data object Duplicate : InsertResult
    data object Tombstoned : InsertResult
    data object Expired : InsertResult

    /** Store ou étiquette pleins d'enveloppes non évinçables : enveloppe `RELAY` refusée. */
    data object Full : InsertResult
}

data class PurgeResult(val expiredEnvelopes: Int, val purgedTombstones: Int)

/**
 * Store local des enveloppes et de leur cycle de vie (architecture mesh v0.1, section 3).
 *
 *  - **Création** : [insert] avec [Origin.SELF] ; toujours accepté, même store plein.
 *  - **Stockage** : [insert] avec [Origin.RELAY] ; refusé s'il est déjà connu,
 *    sous pierre tombale ou expiré.
 *  - **Acquittement 1:1** : [applyAck] supprime la `DATA` dont le champ `ack` vaut
 *    H(jeton) et la remplace par une pierre tombale.
 *  - **Expiration** : [purge] supprime les enveloppes à `createdAt + lifetime + 2 h`.
 *  - **Pierres tombales** : gardées jusqu'à `createdAt + lifetime + 3 h`, au-delà de
 *    la fenêtre d'acceptation de 2 h, pour qu'une enveloppe retirée ne revienne pas.
 *
 * Éviction quand un plafond est atteint : expirées d'abord, puis les `RELAY` les
 * plus proches de l'expiration. `hopCount` n'intervient jamais ; les `SELF` ne
 * sont jamais évincées avant expiration.
 *
 * Toutes les méthodes sont synchronisées : le store est partagé entre transports.
 */
class EnvelopeStore(
    private val backend: StoreBackend = InMemoryStoreBackend(),
    private val limits: StoreLimits = StoreLimits()
) {

    @Synchronized
    fun insert(envelope: Envelope, origin: Origin, nowSeconds: Long): InsertResult {
        val id = envelope.id
        if (backend.get(id) != null) return InsertResult.Duplicate
        if (backend.hasTombstone(id)) return InsertResult.Tombstoned
        if (envelope.isExpired(nowSeconds)) return InsertResult.Expired

        // Un ACK arrivé avant sa DATA : la DATA est déjà acquittée, inutile de la stocker ni de la relayer.
        if (envelope.kind == EnvelopeKind.DATA && isAcknowledgedByStoredAck(envelope)) {
            backend.putTombstone(id, tombstonePurgeAt(envelope))
            return InsertResult.Tombstoned
        }

        // On choisit d'abord les victimes sans rien modifier : si l'enveloppe est
        // finalement refusée, aucune autre n'aura été sacrifiée pour rien.
        val all = backend.all()
        val victims = LinkedHashSet<StoredEnvelope>()

        // Plafond par étiquette : seules les enveloppes de la même étiquette sont candidates.
        val sameTag = all.filter { it.envelope.destTag.contentEquals(envelope.destTag) }
        if (sameTag.size >= limits.maxPerDestTag) {
            val victim = evictionCandidates(sameTag, nowSeconds).firstOrNull()
            when {
                victim != null -> victims += victim
                origin == Origin.RELAY -> return InsertResult.Full
            }
        }

        // Plafonds globaux : nombre et octets.
        var count = all.size - victims.size
        var bytes = all.sumOf { it.envelope.size.toLong() } - victims.sumOf { it.envelope.size.toLong() }
        val pool = evictionCandidates(all, nowSeconds).filterNot { it in victims }.iterator()
        while (count + 1 > limits.maxEnvelopes || bytes + envelope.size > limits.maxBytes) {
            if (!pool.hasNext()) {
                if (origin == Origin.RELAY) return InsertResult.Full
                break   // SELF : accepté au-delà du plafond, l'utilisateur ne perd jamais son envoi
            }
            val victim = pool.next()
            victims += victim
            count -= 1
            bytes -= victim.envelope.size
        }

        victims.forEach { evict(it, nowSeconds) }
        backend.put(StoredEnvelope(envelope, origin, nowSeconds))
        // Un ACK supprime la DATA qu'il acquitte, puis reste stocké pour être relayé jusqu'à sa durée de vie.
        if (envelope.kind == EnvelopeKind.ACK) applyAck(envelope.ack)
        return InsertResult.Stored(victims.map { it.id })
    }

    /**
     * Retire du store une enveloppe livrée à cet appareil : inutile de la relayer, et la pierre
     * tombale empêche qu'elle revienne. Sans effet si elle n'est pas (ou plus) stockée.
     */
    @Synchronized
    fun markDelivered(id: EnvelopeId): Boolean {
        val entry = backend.get(id) ?: return false
        removeWithTombstone(entry)
        return true
    }

    /**
     * Applique un acquittement 1:1 : si une enveloppe `DATA` porte H([ackToken]) dans
     * son champ `ack`, elle est supprimée et passe sous pierre tombale.
     * Renvoie l'ID supprimé, ou null si aucune ne correspond (jeton faux ou inconnu ici).
     */
    @Synchronized
    fun applyAck(ackToken: ByteArray): EnvelopeId? {
        if (ackToken.size != Envelope.FIELD_SIZE || ackToken.all { it == 0.toByte() }) return null
        val expected = AckTokens.hash(ackToken)
        val match = backend.all().firstOrNull {
            it.envelope.kind == EnvelopeKind.DATA && it.envelope.ack.contentEquals(expected)
        } ?: return null
        removeWithTombstone(match)
        return match.id
    }

    /** Supprime les enveloppes expirées et les pierres tombales échues. */
    @Synchronized
    fun purge(nowSeconds: Long): PurgeResult {
        val expired = backend.all().filter { it.envelope.isExpired(nowSeconds) }
        expired.forEach(::removeWithTombstone)
        return PurgeResult(expired.size, backend.purgeTombstones(nowSeconds))
    }

    @Synchronized
    fun get(id: EnvelopeId): StoredEnvelope? = backend.get(id)

    @Synchronized
    fun contains(id: EnvelopeId): Boolean = backend.get(id) != null

    /** Vrai si l'ID est connu, stocké ou sous pierre tombale : inutile de le redemander. */
    @Synchronized
    fun isKnown(id: EnvelopeId): Boolean = backend.get(id) != null || backend.hasTombstone(id)

    @Synchronized
    fun all(): List<StoredEnvelope> = backend.all()

    @Synchronized
    fun stats(): StoreStats {
        val all = backend.all()
        return StoreStats(
            envelopes = all.size,
            bytes = all.sumOf { it.envelope.size.toLong() },
            selfEnvelopes = all.count { it.origin == Origin.SELF },
            tombstones = backend.tombstoneCount()
        )
    }

    // ── Interne ──────────────────────────────────────────────────────────────

    /** Vrai si un `ACK` déjà stocké porte le jeton dont le hachage figure dans le champ `ack` de [data]. */
    private fun isAcknowledgedByStoredAck(data: Envelope): Boolean {
        val wanted = data.ack
        if (wanted.all { it == 0.toByte() }) return false   // pas d'ACK demandé (rooms)
        return backend.all().any {
            it.envelope.kind == EnvelopeKind.ACK &&
                it.envelope.ack.size == Envelope.FIELD_SIZE &&
                AckTokens.hash(it.envelope.ack).contentEquals(wanted)
        }
    }

    /** Expirées d'abord, puis `RELAY` par expiration la plus proche. Jamais de `SELF` non expirée. */
    private fun evictionCandidates(entries: List<StoredEnvelope>, nowSeconds: Long): List<StoredEnvelope> {
        val (expired, live) = entries.partition { it.envelope.isExpired(nowSeconds) }
        val relays = live.filter { it.origin == Origin.RELAY }.sortedBy { it.envelope.expiresAt }
        return expired + relays
    }

    private fun evict(entry: StoredEnvelope, nowSeconds: Long) {
        // Une enveloppe expirée passe sous pierre tombale ; une évincée vivante non,
        // pour pouvoir la reprendre plus tard si la place se libère.
        if (entry.envelope.isExpired(nowSeconds)) removeWithTombstone(entry) else backend.remove(entry.id)
    }

    private fun removeWithTombstone(entry: StoredEnvelope) {
        backend.remove(entry.id)
        backend.putTombstone(entry.id, tombstonePurgeAt(entry.envelope))
    }

    companion object {
        /** Les pierres tombales survivent 1 h de plus que la fenêtre d'acceptation. */
        const val TOMBSTONE_EXTRA_SECONDS = 3600L

        fun tombstonePurgeAt(envelope: Envelope): Long = envelope.expiresAt + TOMBSTONE_EXTRA_SECONDS
    }
}

data class StoreStats(
    val envelopes: Int,
    val bytes: Long,
    val selfEnvelopes: Int,
    val tombstones: Int
)
