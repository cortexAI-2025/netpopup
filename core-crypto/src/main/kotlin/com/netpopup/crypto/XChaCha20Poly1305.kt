package com.netpopup.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * XChaCha20-Poly1305 (draft-irtf-cfrg-xchacha-03) : HChaCha20 dérive une sous-clé
 * depuis les 16 premiers octets du nonce de 24 octets, puis ChaCha20-Poly1305
 * (RFC 8439) du JDK/Android chiffre avec les 8 octets restants.
 *
 * Écrit sur le chiffre du JDK plutôt qu'avec libsodium : pas de bibliothèque
 * native ni de JNA de plus dans l'APK. Vérifié par les vecteurs du brouillon.
 */
object XChaCha20Poly1305 {
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 24
    const val TAG_SIZE = 16

    private val random = SecureRandom()

    fun newNonce(): ByteArray = ByteArray(NONCE_SIZE).also(random::nextBytes)

    fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        run(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext)

    /** @throws GeneralSecurityException si l'étiquette d'authentification est fausse. */
    fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray =
        run(Cipher.DECRYPT_MODE, key, nonce, aad, ciphertext)

    private fun run(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        require(key.size == KEY_SIZE) { "key must be $KEY_SIZE bytes" }
        require(nonce.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes" }
        val subKey = hChaCha20(key, nonce.copyOfRange(0, 16))
        val iv = ByteArray(12)
        System.arraycopy(nonce, 16, iv, 4, 8)
        val cipher = newCipher()
        cipher.init(mode, SecretKeySpec(subKey, "ChaCha20"), IvParameterSpec(iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }

    /** JDK : « ChaCha20-Poly1305 » ; Android (Conscrypt, API 28+) : « ChaCha20/Poly1305/NoPadding ». */
    private fun newCipher(): Cipher = try {
        Cipher.getInstance("ChaCha20-Poly1305")
    } catch (_: GeneralSecurityException) {
        Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
    }

    internal fun hChaCha20(key: ByteArray, input16: ByteArray): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, i * 4)
        for (i in 0 until 4) s[12 + i] = le32(input16, i * 4)
        repeat(10) {
            quarter(s, 0, 4, 8, 12); quarter(s, 1, 5, 9, 13)
            quarter(s, 2, 6, 10, 14); quarter(s, 3, 7, 11, 15)
            quarter(s, 0, 5, 10, 15); quarter(s, 1, 6, 11, 12)
            quarter(s, 2, 7, 8, 13); quarter(s, 3, 4, 9, 14)
        }
        val out = ByteArray(32)
        val words = intArrayOf(s[0], s[1], s[2], s[3], s[12], s[13], s[14], s[15])
        for (i in words.indices) for (b in 0 until 4) out[i * 4 + b] = (words[i] ushr (8 * b)).toByte()
        return out
    }

    private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(16)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(12)
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(8)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(7)
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
}
