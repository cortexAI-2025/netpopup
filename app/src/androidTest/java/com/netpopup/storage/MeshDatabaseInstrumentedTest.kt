package com.netpopup.storage

import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * À lancer sur un appareil ou un émulateur (`connectedDebugAndroidTest`) : la CI ne les exécute pas.
 * Voir aussi docs/storage-manual-test.md pour la vérification de `adb backup`.
 */
@RunWith(AndroidJUnit4::class)
class MeshDatabaseInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: MeshDatabase

    @Before fun setUp() {
        database = MeshDatabase(context)
        database.resetAfterKeyLoss() // état neuf
        database = MeshDatabase(context)
    }

    @After fun tearDown() = database.resetAfterKeyLoss()

    @Test fun databaseFileIsNotReadableWithoutTheKey() {
        database.blockList.block("empreinte-secrete", 1)
        database.sql.close()
        val file = context.getDatabasePath(MeshDatabase.FILE_NAME)
        val header = file.inputStream().use { it.readNBytes(16) }
        assertFalse("en-tête SQLite en clair", String(header, Charsets.ISO_8859_1).startsWith("SQLite format 3"))
        assertFalse("contenu en clair", file.readBytes().toString(Charsets.ISO_8859_1).contains("empreinte-secrete"))
        assertThrows(SQLiteException::class.java) {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
                it.rawQuery("SELECT count(*) FROM blocked_user", null).use { c -> c.moveToFirst() }
            }
        }
    }

    @Test fun dataSurvivesReopeningWithTheStoredKey() {
        database.blockList.block("abc", 1)
        database.sql.close()
        val reopened = MeshDatabase(context)
        assertEquals(setOf("abc"), reopened.blockList.all())
        reopened.sql.close()
    }

    @Test fun lostKeyIsReportedNotSilentlyReset() {
        database.blockList.block("abc", 1)
        database.sql.close()
        context.noBackupFilesDir.resolve("mesh_db.key").delete()
        assertThrows(DatabaseKeyLostException::class.java) { MeshDatabase(context).sql }
    }

    @Test fun backupIsDisabled() {
        database.sql // force la création de la clé avant de vérifier son emplacement
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertTrue(context.noBackupFilesDir.resolve("mesh_db.key").exists())
    }
}
