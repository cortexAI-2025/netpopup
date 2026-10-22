package com.netpopup.store.sql

/**
 * Liste de blocage locale, par empreinte de clé d'identité (jamais par pseudo). Stockée dans la base
 * chiffrée ; remplace la liste du DataStore, qui n'est pas chiffré.
 */
class SqlBlockList(private val db: SqlDatabase) {
    fun block(identityKey: String, nowMillis: Long) {
        db.execute("INSERT OR IGNORE INTO blocked_user (identity_key, blocked_at) VALUES (?, ?)", identityKey, nowMillis)
    }

    fun unblock(identityKey: String) {
        db.execute("DELETE FROM blocked_user WHERE identity_key = ?", identityKey)
    }

    fun isBlocked(identityKey: String): Boolean =
        db.query("SELECT 1 FROM blocked_user WHERE identity_key = ?", identityKey) { 1 }.isNotEmpty()

    fun all(): Set<String> = db.query("SELECT identity_key FROM blocked_user") { it.string(0) }.toSet()

    /** Reprend l'ancienne liste du DataStore ; sans effet sur ce qui est déjà bloqué. Renvoie le nombre ajouté. */
    fun importLegacy(ids: Collection<String>, nowMillis: Long): Int {
        val before = all().size
        db.transaction { ids.forEach { block(it, nowMillis) } }
        return all().size - before
    }
}
