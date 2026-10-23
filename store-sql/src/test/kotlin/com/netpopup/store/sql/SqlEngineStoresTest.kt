package com.netpopup.store.sql

import com.netpopup.dtn.chat.AdmissionPolicy
import com.netpopup.dtn.chat.ChatMessage
import com.netpopup.dtn.chat.Conversation
import com.netpopup.dtn.chat.ConversationId
import com.netpopup.dtn.chat.ConversationKind
import com.netpopup.dtn.chat.DeliveryState
import com.netpopup.dtn.chat.MessageId
import com.netpopup.dtn.direct.PendingSend
import com.netpopup.dtn.envelope.EnvelopeId
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SqlEngineStoresTest {
    @get:Rule val tmp = TemporaryFolder()
    private val opened = ArrayList<JdbcSqlDatabase>()

    @After fun close() = opened.forEach { runCatching { it.close() } }

    private fun open(name: String = "e.db"): JdbcSqlDatabase =
        JdbcSqlDatabase.open(File(tmp.root, name)).also { opened += it; Schema.migrate(it) }

    private fun msg(id: String, conv: String = "c1", at: Long = 1_000, own: Boolean = false, expires: Long = at + 10_000) = ChatMessage(
        MessageId(id), ConversationId(conv), if (own) "me" else "peer", "Alice", "texte $id", at, expires, own,
        if (own) DeliveryState.QUEUED else DeliveryState.RECEIVED,
    )

    @Test fun upgradeFromVersionOneKeepsExistingDataAndAddsTheEngineTables() {
        val db = JdbcSqlDatabase.open(File(tmp.root, "old.db")).also { opened += it }
        // Une base créée par la version 1 du schéma.
        db.execute("CREATE TABLE blocked_user (identity_key TEXT PRIMARY KEY, blocked_at INTEGER NOT NULL)")
        db.execute("INSERT INTO blocked_user VALUES ('aa', 1)")
        db.execute("PRAGMA user_version = 1")
        // migrate() rejouerait V1 sur une base v1 : il doit seulement appliquer V2.
        Schema.migrate(db)
        assertEquals(Schema.VERSION, Schema.currentVersion(db))
        assertEquals(setOf("aa"), SqlBlockList(db).all())
        SqlStateStore(db).put("k", "i", byteArrayOf(1)) // la table engine_state existe
    }

    @Test fun stateStoreRoundTripsAndDeletesByPrefix() {
        val s = SqlStateStore(open())
        s.put("room", "r1", byteArrayOf(1, 2)); s.put("room", "r2", byteArrayOf(3))
        s.put("sent", "r1:0:aa", byteArrayOf(0)); s.put("sent", "r1:0:bb", byteArrayOf(0)); s.put("sent", "r2:0:aa", byteArrayOf(0))
        assertArrayEquals(byteArrayOf(1, 2), s.get("room", "r1"))
        assertEquals(setOf("r1", "r2"), s.all("room").map { it.first }.toSet())
        s.put("room", "r1", byteArrayOf(9)) // remplace
        assertArrayEquals(byteArrayOf(9), s.get("room", "r1"))
        s.deleteWhereIdStartsWith("sent", "r1:")
        assertEquals(listOf("r2:0:aa"), s.all("sent").map { it.first })
        s.delete("room", "r2")
        assertNull(s.get("room", "r2"))
    }

    @Test fun pendingSendsAreStoredPerPeerAndUpdated() {
        val db = open()
        val store = SqlPendingSendStore(db)
        val a = PendingSend("h1", MessageId("m1"), EnvelopeId("e1"), DeliveryState.QUEUED, false)
        store.save("peerA", a); store.save("peerB", PendingSend("h2", MessageId("m2"), EnvelopeId("e2"), DeliveryState.RELAYED, true))
        store.save("peerA", a.copy(state = DeliveryState.ACKNOWLEDGED))
        assertEquals(listOf(DeliveryState.ACKNOWLEDGED), store.load("peerA").map { it.state })
        assertEquals(true, store.load("peerB").single().control)
        store.deleteWhereEnvelopeIn(listOf(EnvelopeId("e1")))
        assertTrue(store.load("peerA").isEmpty())
    }

    @Test fun conversationsPersistWithTheirPolicyAndCascadeOnDelete() {
        val db = open()
        val convs = SqlConversationDao(db); val msgs = SqlMessageDao(db)
        convs.upsert(Conversation(ConversationId("c1"), ConversationKind.ROOM, "Voisins", AdmissionPolicy.CREATOR_ONLY, true), 1)
        convs.upsert(Conversation(ConversationId("local"), ConversationKind.LOCAL_PUBLIC, "Local"), 0)
        msgs.insert(msg("m1")); msgs.insert(msg("m2", conv = "local"))
        val all = convs.all()
        assertEquals(listOf("local", "c1"), all.map { it.id.value })
        assertEquals(AdmissionPolicy.CREATOR_ONLY, all.last().admission)
        assertNull(all.first().admission)
        assertTrue(all.last().keyRotationPending)
        convs.delete(ConversationId("c1"))
        assertEquals(listOf("m2"), msgs.live(0).map { it.id.value })
    }

    @Test fun messagesKeepOrderStateReadMarkAndExpiry() {
        val db = open()
        val msgs = SqlMessageDao(db)
        msgs.insert(msg("b", at = 2_000)); msgs.insert(msg("a", at = 1_000, own = true)); msgs.insert(msg("old", at = 10, expires = 500))
        assertEquals(listOf("old", "a", "b"), msgs.live(0).map { it.id.value })
        assertEquals(listOf("a", "b"), msgs.live(600).map { it.id.value })

        msgs.updateState(MessageId("a"), DeliveryState.ACKNOWLEDGED)
        assertEquals(DeliveryState.ACKNOWLEDGED, msgs.live(600).first { it.id.value == "a" }.deliveryState)
        msgs.markRead(ConversationId("c1"), 5_000)
        val live = msgs.live(600)
        assertEquals(5_000L, live.first { it.id.value == "b" }.readAt)
        assertNull(live.first { it.id.value == "a" }.readAt) // ses propres messages ne sont pas « lus »

        assertEquals(1, msgs.purgeExpired(600))
        assertFalse(msgs.exists(MessageId("old")))
        msgs.delete(MessageId("b"))
        assertFalse(msgs.exists(MessageId("b")))
    }

    @Test fun trimKeepsTheMostRecentMessagesOfOneConversationOnly() {
        val msgs = SqlMessageDao(open())
        (1..5).forEach { msgs.insert(msg("m$it", at = it * 100L, expires = 99_999)) }
        msgs.insert(msg("other", conv = "c2", at = 1, expires = 99_999))
        assertEquals(2, msgs.trim(ConversationId("c1"), 3))
        assertEquals(listOf("other", "m3", "m4", "m5"), msgs.live(0).map { it.id.value }.let { l -> l.sortedBy { if (it == "other") "" else it } })
        assertEquals(0, msgs.trim(ConversationId("c1"), 3))
    }
}
