package com.netpopup.store.sql

import com.netpopup.dtn.envelope.AckTokens
import com.netpopup.dtn.envelope.Envelope
import com.netpopup.dtn.envelope.EnvelopeKind
import com.netpopup.dtn.store.EnvelopeStore
import com.netpopup.dtn.store.InsertResult
import com.netpopup.dtn.store.Origin
import com.netpopup.dtn.store.StoreLimits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SqlStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val opened = ArrayList<JdbcSqlDatabase>()
    private val t0 = 1_700_000_000L

    private fun open(): JdbcSqlDatabase =
        JdbcSqlDatabase.open(java.io.File(tmp.root, "mesh.db")).also { opened += it; Schema.migrate(it) }

    @After fun close() = opened.forEach { runCatching { it.close() } }

    private fun envelope(tag: Int = 1, ack: ByteArray = ByteArray(16), kind: EnvelopeKind = EnvelopeKind.DATA, createdAt: Long = t0, size: Int = 60) =
        Envelope(kind, ByteArray(16) { tag.toByte() }, createdAt, 3600, ack = ack, payload = ByteArray(size) { (it + tag).toByte() })

    // ── Schéma ───────────────────────────────────────────────────────────────

    @Test fun migrateCreatesAllTablesOnce() {
        val db = open()
        assertEquals(Schema.VERSION, Schema.currentVersion(db))
        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.string(0) }.toSet()
        for (t in listOf("envelope", "tombstone", "message", "conversation", "blocked_user", "signal_identity", "signal_session", "signal_sender_key")) {
            assertTrue("table $t", t in tables)
        }
        Schema.migrate(db) // idempotent
        assertEquals(Schema.VERSION, Schema.currentVersion(db))
    }

    @Test fun refusesADatabaseNewerThanTheCode() {
        val db = open()
        db.execute("PRAGMA user_version = ${Schema.VERSION + 1}")
        assertThrows(IllegalStateException::class.java) { Schema.migrate(db) }
    }

    @Test fun failedMigrationRollsBack() {
        val db = JdbcSqlDatabase.open(java.io.File(tmp.root, "x.db")).also { opened += it }
        runCatching { db.transaction { db.execute("CREATE TABLE a (x INTEGER)"); error("boom") } }
        assertTrue(db.query("SELECT name FROM sqlite_master WHERE name = 'a'") { 1 }.isEmpty())
    }

    // ── Store d'enveloppes ───────────────────────────────────────────────────

    @Test fun storeSurvivesARestart() {
        val e = envelope()
        run {
            val db = open()
            val store = EnvelopeStore(SqlStoreBackend(db))
            assertTrue(store.insert(e, Origin.SELF, t0) is InsertResult.Stored)
            db.close()
        }
        val store = EnvelopeStore(SqlStoreBackend(open()))
        val back = store.get(e.id)!!
        assertEquals(Origin.SELF, back.origin)
        assertEquals(e.id, back.envelope.id)
        assertEquals(InsertResult.Duplicate, store.insert(e, Origin.RELAY, t0))
    }

    @Test fun tombstonesAndAckRemovalSurviveARestart() {
        val token = AckTokens.newToken()
        val data = envelope(tag = 2, ack = AckTokens.hash(token))
        run {
            val db = open()
            val store = EnvelopeStore(SqlStoreBackend(db))
            store.insert(data, Origin.RELAY, t0)
            assertEquals(data.id, store.applyAck(token))
            db.close()
        }
        val store = EnvelopeStore(SqlStoreBackend(open()))
        assertFalse(store.contains(data.id))
        assertEquals(InsertResult.Tombstoned, store.insert(data, Origin.RELAY, t0))
        assertEquals(1, store.stats().tombstones)
    }

    @Test fun purgeAndEvictionArePersisted() {
        val limits = StoreLimits(maxEnvelopes = 2, maxPerDestTag = 10)
        val db = open()
        val store = EnvelopeStore(SqlStoreBackend(db), limits)
        val soon = envelope(tag = 1, createdAt = t0)
        val later = envelope(tag = 2, createdAt = t0 + 100)
        val newest = envelope(tag = 3, createdAt = t0 + 200)
        store.insert(soon, Origin.RELAY, t0)
        store.insert(later, Origin.RELAY, t0)
        val result = store.insert(newest, Origin.RELAY, t0) as InsertResult.Stored
        assertEquals(listOf(soon.id), result.evicted)
        db.close()
        val reopened = EnvelopeStore(SqlStoreBackend(open()), limits)
        assertEquals(setOf(later.id, newest.id), reopened.all().map { it.id }.toSet())
        val purge = reopened.purge(newest.expiresAt + 1)
        assertEquals(2, purge.expiredEnvelopes)
        assertEquals(0, reopened.stats().envelopes)
    }

    @Test fun corruptedRowIsDroppedOnLoad() {
        val db = open()
        val good = envelope(tag = 1)
        EnvelopeStore(SqlStoreBackend(db)).insert(good, Origin.RELAY, t0)
        // Ligne dont l'identifiant ne correspond plus au contenu : altérée sur le disque.
        db.execute(
            "INSERT INTO envelope (id, kind, dest_tag, created_at, lifetime, hop_limit, hop_count, ack, payload, origin, stored_at, expires_at) " +
                "VALUES ('00112233445566778899aabbccddeeff', 1, ?, ?, 3600, 8, 0, ?, ?, 1, ?, ?)",
            ByteArray(16), t0, ByteArray(16), ByteArray(60), t0, t0 + 3600,
        )
        // Et une autre, au contenu invalide (type inconnu).
        db.execute(
            "INSERT INTO envelope (id, kind, dest_tag, created_at, lifetime, hop_limit, hop_count, ack, payload, origin, stored_at, expires_at) " +
                "VALUES ('ffffffffffffffffffffffffffffffff', 99, ?, ?, 3600, 8, 0, ?, ?, 1, ?, ?)",
            ByteArray(16), t0, ByteArray(16), ByteArray(60), t0, t0 + 3600,
        )
        val store = EnvelopeStore(SqlStoreBackend(db))
        assertEquals(listOf(good.id), store.all().map { it.id })
        assertEquals(1, db.query("SELECT COUNT(*) FROM envelope") { it.int(0) }.first())
    }

    // ── Blocages ─────────────────────────────────────────────────────────────

    @Test fun blockListPersistsAndImportsTheLegacyList() {
        val db = open()
        val list = SqlBlockList(db)
        list.block("aa11", 1)
        list.block("aa11", 2) // idempotent
        assertTrue(list.isBlocked("aa11"))
        assertEquals(2, list.importLegacy(listOf("old1", "old2", "aa11"), 3))
        db.close()
        val reopened = SqlBlockList(open())
        assertEquals(setOf("aa11", "old1", "old2"), reopened.all())
        reopened.unblock("old1")
        assertFalse(reopened.isBlocked("old1"))
    }
}
