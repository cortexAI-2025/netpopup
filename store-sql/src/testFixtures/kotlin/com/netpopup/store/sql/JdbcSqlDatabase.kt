package com.netpopup.store.sql

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/** SQLite (JDBC) pour les tests de la JVM ; même contrat que l'implémentation SQLCipher de l'app. */
class JdbcSqlDatabase(private val conn: Connection) : SqlDatabase, AutoCloseable {
    private var depth = 0

    override fun execute(sql: String, vararg args: Any?) {
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.execute()
        }
    }

    override fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T> =
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs ->
                val row = JdbcRow(rs)
                val out = ArrayList<T>()
                while (rs.next()) out += map(row)
                out
            }
        }

    @Synchronized
    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) return block()
        depth++
        conn.autoCommit = false
        try {
            val result = block()
            conn.commit()
            return result
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
            depth--
        }
    }

    override fun close() = conn.close()

    private fun bind(st: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> st.setObject(idx, null)
                is Int -> st.setLong(idx, a.toLong())
                is Long -> st.setLong(idx, a)
                is String -> st.setString(idx, a)
                is ByteArray -> st.setBytes(idx, a)
                else -> error("unsupported parameter type ${a::class}")
            }
        }
    }

    private class JdbcRow(private val rs: ResultSet) : SqlRow {
        override fun long(index: Int) = rs.getLong(index + 1)
        override fun string(index: Int): String = rs.getString(index + 1)
        override fun blob(index: Int): ByteArray = rs.getBytes(index + 1)
        override fun isNull(index: Int): Boolean { rs.getObject(index + 1); return rs.wasNull() }
    }

    companion object {
        fun open(file: java.io.File): JdbcSqlDatabase =
            JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}"))
    }
}
