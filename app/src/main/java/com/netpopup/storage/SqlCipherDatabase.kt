package com.netpopup.storage

import android.database.Cursor
import com.netpopup.store.sql.SqlDatabase
import com.netpopup.store.sql.SqlRow
import net.zetetic.database.sqlcipher.SQLiteDatabase

/** [SqlDatabase] sur SQLCipher : chiffre tout le fichier (pages, journal et index). */
class SqlCipherDatabase(private val db: SQLiteDatabase) : SqlDatabase {

    override fun execute(sql: String, vararg args: Any?) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, arrayOf(*args))
    }

    override fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T> {
        val cursor = db.rawQuery(sql, *args)
        return cursor.use { c ->
            val row = CursorRow(c)
            val out = ArrayList<T>()
            while (c.moveToNext()) out += map(row)
            out
        }
    }

    /** Les transactions de SQLCipher s'imbriquent : la plus externe valide ou annule l'ensemble. */
    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun close() = db.close()

    private class CursorRow(private val c: Cursor) : SqlRow {
        override fun long(index: Int) = c.getLong(index)
        override fun string(index: Int): String = c.getString(index)
        override fun blob(index: Int): ByteArray = c.getBlob(index)
        override fun isNull(index: Int) = c.isNull(index)
    }
}
