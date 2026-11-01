package com.netpopup.v2.rooms

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographie des salons NetPopUp.
 *
 * Un salon est défini par son nom et son mot de passe : la clé maîtresse en est dérivée avec Argon2id (le mot de passe n'est
 * jamais stocké ni transmis). Deux sous-clés en sortent : l'une chiffre (AES-256-GCM), l'autre produit une étiquette de 8 octets
 * qui permet à un téléphone de reconnaître ses salons sans révéler leur nom.
 *
 * Limites assumées : la confidentialité vaut ce que vaut le mot de passe (un attaquant qui capte des paquets peut tenter de le
 * deviner hors ligne, Argon2id ralentit seulement l'essai) ; tous les membres partagent la même clé, donc il n'y a ni
 * confidentialité persistante ni preuve d'identité à l'intérieur du salon (le pseudo est déclaratif).
 */
object RoomCrypto {
    const val MASTER_BYTES = 32
    const val TAG_BYTES = 8
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128

    // Argon2id : 16 Mio, 3 passes, 1 voie : quelques centaines de millisecondes sur un téléphone d'entrée de gamme.
    private const val ARGON_MEMORY_KIB = 16 * 1024
    private const val ARGON_ITERATIONS = 3
    private const val ARGON_PARALLELISM = 1

    private val random = SecureRandom()

    /** Même salon, quelle que soit la casse ou les espaces en trop. */
    fun normalizeName(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT).removePrefix("#").trim()

    /** Clé maîtresse du salon (32 octets) : Argon2id(mot de passe, sel dérivé du nom normalisé). */
    fun deriveMasterKey(name: String, password: String): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256")
            .digest("netpopup-room-v1|${normalizeName(name)}".toByteArray(Charsets.UTF_8))
            .copyOf(16)
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(ARGON_ITERATIONS)
            .withMemoryAsKB(ARGON_MEMORY_KIB)
            .withParallelism(ARGON_PARALLELISM)
            .withSalt(salt)
            .build()
        val out = ByteArray(MASTER_BYTES)
        val pwd = password.toByteArray(Charsets.UTF_8)
        try {
            Argon2BytesGenerator().apply { init(params) }.generateBytes(pwd, out)
        } finally {
            pwd.fill(0)
        }
        return out
    }

    private fun hmac(key: ByteArray, label: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(label.toByteArray(Charsets.UTF_8))

    /** Étiquette publique du salon : 8 octets, ne permettent pas de retrouver le nom ni le mot de passe. */
    fun tag(master: ByteArray): ByteArray = hmac(master, "netpopup-tag-v1").copyOf(TAG_BYTES)

    private fun encKey(master: ByteArray) = SecretKeySpec(hmac(master, "netpopup-enc-v1"), "AES")

    /** nonce(12) ‖ AES-256-GCM(clair), l'étiquette du salon étant authentifiée (AAD). */
    fun seal(master: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encKey(master), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(tag(master))
        return nonce + cipher.doFinal(plaintext)
    }

    /** Le clair, ou `null` si la clé n'est pas la bonne ou si le message a été altéré. */
    fun open(master: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < NONCE_BYTES + GCM_TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encKey(master), GCMParameterSpec(GCM_TAG_BITS, sealed, 0, NONCE_BYTES))
            cipher.updateAAD(tag(master))
            cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
        } catch (e: Exception) {
            null
        }
    }

    fun newMessageId(): ByteArray = ByteArray(16).also(random::nextBytes)
}
