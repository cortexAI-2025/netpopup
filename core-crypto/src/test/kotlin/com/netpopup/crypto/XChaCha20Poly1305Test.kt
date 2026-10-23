package com.netpopup.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

class XChaCha20Poly1305Test {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    /** draft-irtf-cfrg-xchacha-03, section 2.2.1. */
    @Test fun hChaCha20_draftVector() {
        val key = ByteArray(32) { it.toByte() }
        val input = hex("000000090000004a0000000031415927")
        assertEquals(
            "82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc",
            XChaCha20Poly1305.hChaCha20(key, input).toHex(),
        )
    }

    private val plaintext = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for " +
        "the future, sunscreen would be it.").toByteArray()
    private val aad = hex("50515253c0c1c2c3c4c5c6c7")
    private val key = ByteArray(32) { (0x80 + it).toByte() }
    private val nonce = ByteArray(24) { (0x40 + it).toByte() }

    /** draft-irtf-cfrg-xchacha-03, annexe A.3.1 (recoupé avec libsodium). */
    @Test fun aead_draftVector() {
        val expected = "bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb731c7f1b0b4aa6440bf3a82f" +
            "4eda7e39ae64c6708c54c216cb96b72e1213b4522f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565" +
            "d3fff921f9664c97637da9768812f615c68b13b52ec0875924c1c7987947deafd8780acf49"
        val ct = XChaCha20Poly1305.encrypt(key, nonce, aad, plaintext)
        assertEquals(expected, ct.toHex())
        assertArrayEquals(plaintext, XChaCha20Poly1305.decrypt(key, nonce, aad, ct))
    }

    @Test fun tamperedCiphertextOrAadIsRejected() {
        val ct = XChaCha20Poly1305.encrypt(key, nonce, aad, plaintext)
        val flipped = ct.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { XChaCha20Poly1305.decrypt(key, nonce, aad, flipped) }
        assertThrows(GeneralSecurityException::class.java) {
            XChaCha20Poly1305.decrypt(key, nonce, aad + 0, ct)
        }
    }

    @Test fun nonceIsRandomAndRightSize() {
        val a = XChaCha20Poly1305.newNonce()
        assertEquals(24, a.size)
        assertNotEquals(a.toList(), XChaCha20Poly1305.newNonce().toList())
    }

    @Test fun outerLayer_roundTripAndBindsHeader() {
        val envKey = ByteArray(32) { 7 }
        val header = ByteArray(47) { it.toByte() }
        val sealed = OuterLayer.seal(envKey, header, "bonjour".toByteArray())
        assertEquals("bonjour".length + OuterLayer.OVERHEAD, sealed.size)
        assertArrayEquals("bonjour".toByteArray(), OuterLayer.open(envKey, header, sealed))
        // En-tête modifié par un relais, mauvaise clé, payload tronqué : rien ne s'ouvre.
        assertNull(OuterLayer.open(envKey, header.copyOf().also { it[0] = 9 }, sealed))
        assertNull(OuterLayer.open(ByteArray(32) { 8 }, header, sealed))
        assertNull(OuterLayer.open(envKey, header, sealed.copyOf(OuterLayer.OVERHEAD - 1)))
    }

    @Test fun channelKeys_areDeterministicAndSeparated() {
        val secret = ByteArray(32) { 1 }
        val a = ChannelKeys.derive(secret, "room")
        val b = ChannelKeys.derive(secret, "room")
        assertArrayEquals(a.tagKey, b.tagKey)
        assertArrayEquals(a.envKey, b.envKey)
        assertNotEquals(a.tagKey.toList(), a.envKey.toList())
        assertNotEquals(a.tagKey.toList(), ChannelKeys.derive(secret, "pair").tagKey.toList())
        assertThrows(IllegalArgumentException::class.java) { ChannelKeys.derive(ByteArray(16), "room") }
    }
}
