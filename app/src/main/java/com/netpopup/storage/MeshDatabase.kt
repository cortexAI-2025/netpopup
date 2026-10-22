package com.netpopup.storage

import android.content.Context
import com.netpopup.crypto.IdentityVault
import com.netpopup.store.sql.Schema
import com.netpopup.store.sql.SqlBlockList
import com.netpopup.store.sql.SqlIdentityVault
import com.netpopup.store.sql.SqlStoreBackend
import dagger.hilt.android.qualifiers.ApplicationContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Base unique et chiffrée de l'appareil : enveloppes, messages, conversations, blocages et états
 * libsignal. Ouverte à la demande (jamais dans le fil principal : le Keystore et SQLCipher sont lents).
 *
 * Perte de clé (effacement du Keystore, par exemple) : [open] lève [DatabaseKeyLostException]. Rien n'est
 * effacé automatiquement, car cela détruirait l'identité de l'appareil ; l'interface doit proposer
 * [resetAfterKeyLoss] et prévenir que les contacts devront être rééchangés.
 */
@Singleton
class MeshDatabase @Inject constructor(@ApplicationContext private val context: Context) {
    private val keyStore = DatabaseKeyStore(context)
    private val file: File get() = context.getDatabasePath(FILE_NAME)

    @Volatile private var opened: SqlCipherDatabase? = null

    val sql: SqlCipherDatabase get() = opened ?: synchronized(this) { opened ?: open().also { opened = it } }

    val blockList: SqlBlockList by lazy { SqlBlockList(sql) }
    val storeBackend: SqlStoreBackend by lazy { SqlStoreBackend(sql) }
    val identityVault: IdentityVault by lazy { SqlIdentityVault(sql) }

    private fun open(): SqlCipherDatabase {
        if (file.exists() && !keyStore.exists()) throw DatabaseKeyLostException("database exists but its key is gone")
        file.parentFile?.mkdirs()
        System.loadLibrary("sqlcipher")
        val key = keyStore.getOrCreate()
        // Clé brute de 256 bits (x'…') : pas de dérivation PBKDF2, elle est déjà aléatoire.
        val passphrase = "x'" + key.joinToString("") { "%02x".format(it) } + "'"
        val db = try {
            SQLiteDatabase.openOrCreateDatabase(file, passphrase.toByteArray(), null, null)
        } catch (e: Exception) {
            throw DatabaseKeyLostException("database cannot be opened with its key", e)
        }
        db.rawQuery("PRAGMA secure_delete = ON").close() // les messages effacés ne laissent pas de trace
        return SqlCipherDatabase(db).also { Schema.migrate(it) }
    }

    /** Efface la base et sa clé. Détruit l'identité de l'appareil : à n'appeler qu'avec l'accord de l'utilisateur. */
    @Synchronized
    fun resetAfterKeyLoss() {
        opened?.close()
        opened = null
        listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { it.delete() }
        keyStore.delete()
    }

    companion object {
        const val FILE_NAME = "mesh.db"
    }
}
