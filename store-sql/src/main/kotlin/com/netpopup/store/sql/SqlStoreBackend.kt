package com.netpopup.store.sql

import com.netpopup.dtn.envelope.Envelope
import com.netpopup.dtn.envelope.EnvelopeId
import com.netpopup.dtn.envelope.EnvelopeKind
import com.netpopup.dtn.store.Origin
import com.netpopup.dtn.store.StoreBackend
import com.netpopup.dtn.store.StoredEnvelope

/**
 * Backend persistant du store d'enveloppes.
 *
 * `EnvelopeStore` relit tout le store à chaque insertion (jusqu'à 20 Mio) : les enveloppes sont donc
 * chargées une fois en mémoire à l'ouverture, puis tenues à jour à chaque écriture (écriture
 * directe en base d'abord). La base reste la source de vérité après un redémarrage ; le cache est
 * borné par les plafonds du store.
 *
 * Une ligne dont l'identifiant ne correspond plus au contenu (corruption) est ignorée et supprimée.
 */
class SqlStoreBackend(private val db: SqlDatabase) : StoreBackend {
    private val cache: LinkedHashMap<EnvelopeId, StoredEnvelope> by lazy { load() }

    private fun load(): LinkedHashMap<EnvelopeId, StoredEnvelope> {
        val result = LinkedHashMap<EnvelopeId, StoredEnvelope>()
        val corrupt = ArrayList<String>()
        db.query(
            "SELECT id, kind, dest_tag, created_at, lifetime, hop_limit, hop_count, ack, payload, origin, stored_at " +
                "FROM envelope ORDER BY stored_at, rowid",
        ) { r ->
            val entry = runCatching {
                val kind = EnvelopeKind.fromCode(r.int(1).toByte()) ?: error("unknown kind")
                val envelope = Envelope(
                    kind, r.blob(2), r.long(3), r.int(4), r.int(5), r.int(6), r.blob(7), r.blob(8),
                )
                check(envelope.id.hex == r.string(0)) { "id mismatch" }
                StoredEnvelope(envelope, Origin.entries[r.int(9)], r.long(10))
            }.getOrNull()
            if (entry != null) result[entry.id] = entry else corrupt += r.string(0)
        }
        corrupt.forEach { db.execute("DELETE FROM envelope WHERE id = ?", it) }
        return result
    }

    override fun get(id: EnvelopeId): StoredEnvelope? = cache[id]

    override fun put(entry: StoredEnvelope) {
        val e = entry.envelope
        db.execute(
            "INSERT OR REPLACE INTO envelope (id, kind, dest_tag, created_at, lifetime, hop_limit, hop_count, " +
                "ack, payload, origin, stored_at, expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            entry.id.hex, e.kind.code.toInt(), e.destTag, e.createdAt, e.lifetime, e.hopLimit, e.hopCount,
            e.ack, e.payload, entry.origin.ordinal, entry.storedAt, e.expiresAt,
        )
        cache[entry.id] = entry
    }

    override fun remove(id: EnvelopeId) {
        db.execute("DELETE FROM envelope WHERE id = ?", id.hex)
        cache.remove(id)
    }

    override fun all(): List<StoredEnvelope> = cache.values.toList()

    override fun hasTombstone(id: EnvelopeId): Boolean =
        db.query("SELECT 1 FROM tombstone WHERE id = ?", id.hex) { 1 }.isNotEmpty()

    override fun putTombstone(id: EnvelopeId, purgeAt: Long) {
        db.execute("INSERT OR REPLACE INTO tombstone (id, purge_at) VALUES (?, ?)", id.hex, purgeAt)
    }

    override fun purgeTombstones(nowSeconds: Long): Int = db.transaction {
        val n = db.query("SELECT COUNT(*) FROM tombstone WHERE purge_at < ?", nowSeconds) { it.int(0) }.first()
        db.execute("DELETE FROM tombstone WHERE purge_at < ?", nowSeconds)
        n
    }

    override fun tombstoneIds(): List<EnvelopeId> =
        db.query("SELECT id FROM tombstone") { EnvelopeId(it.string(0)) }

    override fun tombstoneCount(): Int = db.query("SELECT COUNT(*) FROM tombstone") { it.int(0) }.first()
}
