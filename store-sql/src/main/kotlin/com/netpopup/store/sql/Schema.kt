package com.netpopup.store.sql

/**
 * Schéma de la base chiffrée (architecture mesh v0.1, section 3). Toute la base est chiffrée
 * par SQLCipher : enveloppes, messages, conversations, blocages et états libsignal.
 *
 * La version est portée par `PRAGMA user_version` ; chaque migration est une liste d'instructions
 * appliquée dans une transaction.
 */
object Schema {
    const val VERSION = 1

    private val V1 = listOf(
        """CREATE TABLE envelope (
            id TEXT PRIMARY KEY, kind INTEGER NOT NULL, dest_tag BLOB NOT NULL,
            created_at INTEGER NOT NULL, lifetime INTEGER NOT NULL, hop_limit INTEGER NOT NULL,
            hop_count INTEGER NOT NULL, ack BLOB NOT NULL, payload BLOB NOT NULL,
            origin INTEGER NOT NULL, stored_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)""",
        "CREATE INDEX envelope_dest_tag ON envelope(dest_tag)",
        "CREATE INDEX envelope_expires_at ON envelope(expires_at)",
        "CREATE TABLE tombstone (id TEXT PRIMARY KEY, purge_at INTEGER NOT NULL)",
        "CREATE INDEX tombstone_purge_at ON tombstone(purge_at)",
        """CREATE TABLE conversation (
            id TEXT PRIMARY KEY, kind INTEGER NOT NULL, title TEXT NOT NULL, admission INTEGER,
            key_rotation_pending INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)""",
        """CREATE TABLE message (
            id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, sender_key TEXT NOT NULL,
            display_name TEXT NOT NULL, body TEXT NOT NULL, sent_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL, is_own INTEGER NOT NULL, delivery_state INTEGER NOT NULL,
            read_at INTEGER)""",
        "CREATE INDEX message_conversation ON message(conversation_id, sent_at)",
        "CREATE INDEX message_expires_at ON message(expires_at)",
        "CREATE TABLE blocked_user (identity_key TEXT PRIMARY KEY, blocked_at INTEGER NOT NULL)",
        // États libsignal
        "CREATE TABLE signal_identity (id INTEGER PRIMARY KEY CHECK (id = 1), key_pair BLOB NOT NULL, registration_id INTEGER NOT NULL)",
        "CREATE TABLE signal_trusted_identity (address TEXT PRIMARY KEY, identity_key BLOB NOT NULL)",
        "CREATE TABLE signal_session (address TEXT PRIMARY KEY, name TEXT NOT NULL, device_id INTEGER NOT NULL, record BLOB NOT NULL)",
        "CREATE INDEX signal_session_name ON signal_session(name)",
        "CREATE TABLE signal_pre_key (id INTEGER PRIMARY KEY, record BLOB NOT NULL)",
        "CREATE TABLE signal_signed_pre_key (id INTEGER PRIMARY KEY, record BLOB NOT NULL)",
        "CREATE TABLE signal_kyber_pre_key (id INTEGER PRIMARY KEY, record BLOB NOT NULL, used INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE signal_sender_key (address TEXT NOT NULL, distribution_id TEXT NOT NULL, record BLOB NOT NULL, PRIMARY KEY (address, distribution_id))",
    )

    private val migrations: List<List<String>> = listOf(V1)

    fun currentVersion(db: SqlDatabase): Int = db.query("PRAGMA user_version") { it.int(0) }.first()

    /** Crée ou met à niveau la base. Refuse une base plus récente que ce code. */
    fun migrate(db: SqlDatabase) {
        val from = currentVersion(db)
        check(from <= VERSION) { "database version $from is newer than supported $VERSION" }
        for (v in from until VERSION) {
            db.transaction {
                migrations[v].forEach { db.execute(it) }
                db.execute("PRAGMA user_version = ${v + 1}")
            }
        }
    }
}
