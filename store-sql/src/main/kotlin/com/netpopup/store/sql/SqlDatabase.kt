package com.netpopup.store.sql

/** Ligne de résultat, lue par index de colonne (à partir de 0). */
interface SqlRow {
    fun long(index: Int): Long
    fun int(index: Int): Int = long(index).toInt()
    fun string(index: Int): String
    fun blob(index: Int): ByteArray
    fun isNull(index: Int): Boolean
}

/**
 * Accès SQL minimal, indépendant de la bibliothèque : SQLCipher dans l'app, SQLite (JDBC) dans
 * les tests de la JVM. Les paramètres sont des `Long`, `Int`, `String`, `ByteArray` ou `null`.
 *
 * Une implémentation doit sérialiser les accès (un seul fil à la fois) et rendre
 * [transaction] atomique : tout ou rien.
 */
interface SqlDatabase {
    fun execute(sql: String, vararg args: Any?)
    fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T>
    fun <T> transaction(block: () -> T): T
}
