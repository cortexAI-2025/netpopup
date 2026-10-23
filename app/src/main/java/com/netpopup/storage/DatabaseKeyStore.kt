package com.netpopup.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** La clé de la base est introuvable ou illisible : les données chiffrées ne sont plus récupérables. */
class DatabaseKeyLostException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Clé de 256 bits de la base SQLCipher, tirée au hasard à la première ouverture puis enveloppée
 * (AES-GCM) par une clé de l'Android Keystore qui ne quitte jamais le matériel sécurisé.
 *
 * Le fichier enveloppé vit dans `noBackupFilesDir` : hors de toute sauvegarde. Restauré sur un autre
 * téléphone sans la clé Keystore, il serait de toute façon inutilisable.
 */
class DatabaseKeyStore(context: Context) {
    private val file = File(context.noBackupFilesDir, KEY_FILE)

    fun exists(): Boolean = file.exists()

    /** Renvoie la clé, en la créant si besoin. @throws DatabaseKeyLostException si elle ne se déchiffre plus. */
    @Synchronized
    fun getOrCreate(): ByteArray {
        if (!file.exists()) return create()
        return try {
            unwrap(file.readBytes())
        } catch (e: GeneralSecurityException) {
            throw DatabaseKeyLostException("database key cannot be unwrapped", e)
        }
    }

    /** Efface la clé enveloppée (réinitialisation volontaire après perte de la clé). */
    @Synchronized
    fun delete() {
        file.delete()
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
    }

    private fun create(): ByteArray {
        val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        val wrapped = wrap(key)
        val tmp = File(file.parentFile, "$KEY_FILE.tmp")
        tmp.writeBytes(wrapped)
        check(tmp.renameTo(file)) { "cannot write database key" }
        return key
    }

    private fun wrap(key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        cipher.updateAAD(AAD)
        return cipher.iv + cipher.doFinal(key)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        if (blob.size <= IV_BYTES) throw GeneralSecurityException("truncated key file")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, blob, 0, IV_BYTES))
        cipher.updateAAD(AAD)
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        // Pas d'authentification utilisateur : le service mesh doit pouvoir ouvrir la base en arrière-plan.
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }   // StrongBox : API 28+, minSdk est 29
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        try {
            generator.init(spec(strongBox = true))
        } catch (e: StrongBoxUnavailableException) {
            generator.init(spec(strongBox = false))
        }
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "netpopup_db_wrap_v1"
        const val KEY_FILE = "mesh_db.key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        val AAD = "netpopup/db-key/v1".toByteArray()
    }
}
