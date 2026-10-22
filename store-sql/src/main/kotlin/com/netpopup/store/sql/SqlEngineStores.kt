package com.netpopup.store.sql

import com.netpopup.dtn.chat.AdmissionPolicy
import com.netpopup.dtn.chat.ChatMessage
import com.netpopup.dtn.chat.Conversation
import com.netpopup.dtn.chat.ConversationId
import com.netpopup.dtn.chat.ConversationKind
import com.netpopup.dtn.chat.DeliveryState
import com.netpopup.dtn.chat.MessageId
import com.netpopup.dtn.direct.PendingSend
import com.netpopup.dtn.direct.PendingSendStore
import com.netpopup.dtn.envelope.EnvelopeId

/**
 * Petit magasin clé-valeur pour l'état sérialisé du moteur (contacts, rooms, demandes d'accès, marqueurs).
 * Les objets sont de petits blobs relus en bloc au démarrage ; ce qui s'interroge (messages,
 * conversations, envois en attente) a ses propres tables.
 */
class SqlStateStore(private val db: SqlDatabase) {
    fun put(kind: String, id: String, data: ByteArray) {
        db.execute("INSERT OR REPLACE INTO engine_state (kind, id, data) VALUES (?, ?, ?)", kind, id, data)
    }

    fun get(kind: String, id: String): ByteArray? =
        db.query("SELECT data FROM engine_state WHERE kind = ? AND id = ?", kind, id) { it.blob(0) }.firstOrNull()

    fun all(kind: String): List<Pair<String, ByteArray>> =
        db.query("SELECT id, data FROM engine_state WHERE kind = ?", kind) { it.string(0) to it.blob(1) }

    fun delete(kind: String, id: String) {
        db.execute("DELETE FROM engine_state WHERE kind = ? AND id = ?", kind, id)
    }

    fun deleteWhereIdStartsWith(kind: String, prefix: String) {
        db.execute("DELETE FROM engine_state WHERE kind = ? AND id LIKE ? ESCAPE '\\'", kind, prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
    }
}

class SqlPendingSendStore(private val db: SqlDatabase) : PendingSendStore {
    override fun load(peer: String): List<PendingSend> =
        db.query("SELECT token_hash, message_id, envelope_id, state, control FROM pending_send WHERE peer = ?", peer) {
            PendingSend(
                tokenHash = it.string(0), messageId = MessageId(it.string(1)), envelopeId = EnvelopeId(it.string(2)),
                state = DeliveryState.entries[it.int(3)], control = it.int(4) != 0,
            )
        }

    override fun save(peer: String, send: PendingSend) {
        db.execute(
            "INSERT OR REPLACE INTO pending_send (token_hash, peer, message_id, envelope_id, state, control) VALUES (?,?,?,?,?,?)",
            send.tokenHash, peer, send.messageId.value, send.envelopeId.hex, send.state.ordinal, if (send.control) 1 else 0,
        )
    }

    /** Oublie les envois dont l'enveloppe a disparu (expirée) pour ne pas grossir sans fin. */
    fun deleteWhereEnvelopeIn(ids: Collection<EnvelopeId>) {
        db.transaction { ids.forEach { db.execute("DELETE FROM pending_send WHERE envelope_id = ?", it.hex) } }
    }

    fun all(): List<PendingSend> =
        db.query("SELECT token_hash, message_id, envelope_id, state, control FROM pending_send") {
            PendingSend(it.string(0), MessageId(it.string(1)), EnvelopeId(it.string(2)), DeliveryState.entries[it.int(3)], it.int(4) != 0)
        }
}

class SqlConversationDao(private val db: SqlDatabase) {
    fun upsert(c: Conversation, createdAt: Long) {
        db.execute(
            "INSERT OR REPLACE INTO conversation (id, kind, title, admission, key_rotation_pending, created_at) VALUES (?,?,?,?,?,?)",
            c.id.value, c.kind.ordinal, c.title, c.admission?.ordinal, if (c.keyRotationPending) 1 else 0, createdAt,
        )
    }

    fun delete(id: ConversationId) {
        db.transaction {
            db.execute("DELETE FROM message WHERE conversation_id = ?", id.value)
            db.execute("DELETE FROM conversation WHERE id = ?", id.value)
        }
    }

    fun all(): List<Conversation> =
        db.query("SELECT id, kind, title, admission, key_rotation_pending FROM conversation ORDER BY created_at, rowid") {
            Conversation(
                id = ConversationId(it.string(0)), kind = ConversationKind.entries[it.int(1)], title = it.string(2),
                admission = if (it.isNull(3)) null else AdmissionPolicy.entries[it.int(3)],
                keyRotationPending = it.int(4) != 0,
            )
        }
}

class SqlMessageDao(private val db: SqlDatabase) {
    fun insert(m: ChatMessage) {
        db.execute(
            "INSERT OR REPLACE INTO message (id, conversation_id, sender_key, display_name, body, sent_at, expires_at, is_own, delivery_state, read_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?)",
            m.id.value, m.conversationId.value, m.senderKey, m.displayName, m.body, m.sentAt, m.expiresAt,
            if (m.isOwn) 1 else 0, m.deliveryState.ordinal, m.readAt,
        )
    }

    fun updateState(id: MessageId, state: DeliveryState) {
        db.execute("UPDATE message SET delivery_state = ? WHERE id = ?", state.ordinal, id.value)
    }

    fun markRead(conversation: ConversationId, nowMillis: Long) {
        db.execute("UPDATE message SET read_at = ? WHERE conversation_id = ? AND is_own = 0 AND read_at IS NULL", nowMillis, conversation.value)
    }

    fun delete(id: MessageId) {
        db.execute("DELETE FROM message WHERE id = ?", id.value)
    }

    fun purgeExpired(nowMillis: Long): Int = db.transaction {
        val n = db.query("SELECT COUNT(*) FROM message WHERE expires_at <= ?", nowMillis) { it.int(0) }.first()
        db.execute("DELETE FROM message WHERE expires_at <= ?", nowMillis)
        n
    }

    /** Garde les [max] messages les plus récents de la conversation ; renvoie le nombre supprimé. */
    fun trim(conversation: ConversationId, max: Int): Int = db.transaction {
        val n = db.query("SELECT COUNT(*) FROM message WHERE conversation_id = ?", conversation.value) { it.int(0) }.first()
        val extra = n - max
        if (extra > 0) {
            db.execute(
                "DELETE FROM message WHERE id IN (SELECT id FROM message WHERE conversation_id = ? ORDER BY sent_at, rowid LIMIT ?)",
                conversation.value, extra,
            )
        }
        extra.coerceAtLeast(0)
    }

    fun exists(id: MessageId): Boolean = db.query("SELECT 1 FROM message WHERE id = ?", id.value) { 1 }.isNotEmpty()

    /** Messages non expirés, du plus ancien au plus récent. */
    fun live(nowMillis: Long): List<ChatMessage> =
        db.query(
            "SELECT id, conversation_id, sender_key, display_name, body, sent_at, expires_at, is_own, delivery_state, read_at " +
                "FROM message WHERE expires_at > ? ORDER BY sent_at, rowid",
            nowMillis,
        ) {
            ChatMessage(
                id = MessageId(it.string(0)), conversationId = ConversationId(it.string(1)), senderKey = it.string(2),
                displayName = it.string(3), body = it.string(4), sentAt = it.long(5), expiresAt = it.long(6),
                isOwn = it.int(7) != 0, deliveryState = DeliveryState.entries[it.int(8)],
                readAt = if (it.isNull(9)) null else it.long(9),
            )
        }
}
