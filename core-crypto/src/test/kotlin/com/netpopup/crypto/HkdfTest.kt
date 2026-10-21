package com.netpopup.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HkdfTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun rfc5869_case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val prk = Hkdf.extract(salt, ikm)
        assertArrayEquals(hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"), prk)
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            Hkdf.expand(prk, info, 42),
        )
    }

    @Test fun rfc5869_case3_emptySaltAndInfo() {
        val ikm = ByteArray(22) { 0x0b }
        assertArrayEquals(
            hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            Hkdf.derive(ikm, null, ByteArray(0), 42),
        )
    }

    @Test fun rejectsInvalidLengths() {
        assertThrows(IllegalArgumentException::class.java) { Hkdf.expand(ByteArray(32), ByteArray(0), 0) }
        assertThrows(IllegalArgumentException::class.java) { Hkdf.expand(ByteArray(32), ByteArray(0), 255 * 32 + 1) }
    }
}
