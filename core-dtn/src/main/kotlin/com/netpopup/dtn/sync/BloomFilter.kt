package com.netpopup.dtn.sync

import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Filtre de Bloom des IDs qu'un pair connaît, envoyé dans la trame `INVENTORY`.
 *
 * Indices par double hachage : `h1 + i·h2 mod m`, h1 et h2 tirés de
 * SHA-256(sel ‖ id). Le sel change à chaque session : un faux positif (un ID
 * que le pair croit à tort déjà connu) ne bloque pas toujours la même enveloppe.
 */
class BloomFilter private constructor(
    val bitCount: Int,
    val hashCount: Int,
    salt: ByteArray,
    bits: ByteArray
) {
    private val saltBytes = salt.copyOf()
    private val bitBytes = bits.copyOf()

    init {
        require(bitCount > 0) { "bitCount must be > 0" }
        require(hashCount in 1..MAX_HASHES) { "hashCount must be in 1..$MAX_HASHES" }
        require(salt.size == SALT_SIZE) { "salt must be $SALT_SIZE bytes" }
        require(bits.size == byteCountFor(bitCount)) { "bit array size does not match bitCount" }
    }

    val salt: ByteArray get() = saltBytes.copyOf()
    val bits: ByteArray get() = bitBytes.copyOf()

    fun add(id: ByteArray) = indices(id).forEach { bitBytes[it ushr 3] = (bitBytes[it ushr 3].toInt() or (1 shl (it and 7))).toByte() }

    fun mightContain(id: ByteArray): Boolean =
        indices(id).all { bitBytes[it ushr 3].toInt() and (1 shl (it and 7)) != 0 }

    private fun indices(id: ByteArray): IntArray {
        val d = MessageDigest.getInstance("SHA-256").apply { update(saltBytes); update(id) }.digest()
        val h1 = d.readUInt(0)
        val h2 = d.readUInt(4) or 1L   // impair : parcourt toutes les positions
        return IntArray(hashCount) { i -> Math.floorMod(h1 + i * h2, bitCount.toLong()).toInt() }
    }

    companion object {
        const val SALT_SIZE = 16
        const val MAX_HASHES = 16

        /** Taille optimale pour [expected] éléments et un taux de faux positifs [falsePositiveRate]. */
        fun create(expected: Int, falsePositiveRate: Double, salt: ByteArray): BloomFilter {
            val n = expected.coerceAtLeast(1)
            val m = ceil(-n * ln(falsePositiveRate) / (ln(2.0) * ln(2.0))).toInt().coerceAtLeast(8)
            val k = (m.toDouble() / n * ln(2.0)).roundToInt().coerceIn(1, MAX_HASHES)
            return BloomFilter(m, k, salt, ByteArray(byteCountFor(m)))
        }

        /** Reconstruit un filtre reçu ; null si ses paramètres sont incohérents. */
        fun fromWire(bitCount: Int, hashCount: Int, salt: ByteArray, bits: ByteArray): BloomFilter? =
            runCatching { BloomFilter(bitCount, hashCount, salt, bits) }.getOrNull()

        fun byteCountFor(bitCount: Int): Int = (bitCount + 7) / 8

        private fun ByteArray.readUInt(offset: Int): Long =
            ((this[offset].toLong() and 0xFF) shl 24) or ((this[offset + 1].toLong() and 0xFF) shl 16) or
                ((this[offset + 2].toLong() and 0xFF) shl 8) or (this[offset + 3].toLong() and 0xFF)
    }
}
