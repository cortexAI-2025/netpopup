package com.netpopup.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactBundleTest {
    private val bob = TestDevice("Bob")

    @Test fun roundTripThroughBytesAndUri() {
        val bundle = bob.bundle()
        val decoded = ContactBundle.decode(bundle.encode())
        assertEquals("Bob", decoded.name)
        assertArrayEquals(bundle.identityKey.serialize(), decoded.identityKey.serialize())
        assertTrue(decoded.hasKyber)
        assertArrayEquals(bundle.encode(), ContactBundle.fromUri(bundle.toUri()).encode())
    }

    @Test fun withoutKyberIsMuchSmaller() {
        val classic = TestDevice("Cleo", withKyber = false).bundle()
        assertFalse(classic.hasKyber)
        assertTrue(classic.encode().size < 250)
        assertTrue(bob.bundle().encode().size in 1700..1900)
        assertEquals("Cleo", ContactBundle.decode(classic.encode()).name)
    }

    @Test fun uriFitsInAQrCode() {
        // Un QR version 40 niveau L contient 2 953 octets ; on garde de la marge pour un scan fiable.
        assertTrue(bob.bundle().toUri().length < 2600)
    }

    @Test fun forgedSignedPreKeySignatureIsRejected() {
        val bytes = bob.bundle().encode()
        val sigStart = 2 + 4 + 33 + 4 + 33
        bytes[sigStart + 5] = (bytes[sigStart + 5].toInt() xor 1).toByte()
        assertThrows(InvalidBundleException::class.java) { ContactBundle.decode(bytes) }
    }

    @Test fun forgedKyberSignatureIsRejected() {
        val bytes = bob.bundle().encode()
        val kyberSigStart = 2 + 4 + 33 + 4 + 33 + 64 + 4 + 1569
        bytes[kyberSigStart + 3] = (bytes[kyberSigStart + 3].toInt() xor 1).toByte()
        assertThrows(InvalidBundleException::class.java) { ContactBundle.decode(bytes) }
    }

    @Test fun swappedIdentityKeyInvalidatesSignatures() {
        // Un attaquant qui remplace IK par la sienne ne peut pas refaire la signature de SPK.
        val bytes = bob.bundle().encode()
        val other = TestDevice("Mallory").bundle().encode()
        System.arraycopy(other, 6, bytes, 6, 33)
        assertThrows(InvalidBundleException::class.java) { ContactBundle.decode(bytes) }
    }

    @Test fun malformedInputsNeverEscapeAsOtherExceptions() {
        val good = bob.bundle().encode()
        val cases = listOf(
            ByteArray(0), ByteArray(1), good.copyOf(good.size - 1), good + 0,
            good.copyOf().also { it[0] = 9 }, good.copyOf().also { it[1] = 0x42 },
            ByteArray(5000), good.copyOf().also { it[it.size - 1 - "Bob".length] = 99 },
        )
        for (c in cases) assertThrows(InvalidBundleException::class.java) { ContactBundle.decode(c) }
        assertThrows(InvalidBundleException::class.java) { ContactBundle.fromUri("https://x") }
        assertThrows(InvalidBundleException::class.java) { ContactBundle.fromUri("netpopup://contact/@@@") }
    }

    @Test fun fuzzedBytesOnlyEverRaiseInvalidBundle() {
        val good = bob.bundle().encode()
        val rnd = java.util.Random(42)
        repeat(300) {
            val mutated = good.copyOf()
            repeat(1 + rnd.nextInt(4)) { mutated[rnd.nextInt(mutated.size)] = rnd.nextInt().toByte() }
            runCatching { ContactBundle.decode(mutated) }.onFailure {
                assertTrue(it.toString(), it is InvalidBundleException)
            }
        }
    }

    @Test fun overlongNameIsRefusedAtEncode() {
        val b = bob.bundle()
        val long = ContactBundle(
            "x".repeat(33), b.registrationId, b.identityKey, b.signedPreKeyId, b.signedPreKey,
            b.signedPreKeySignature, b.kyberPreKeyId, b.kyberPreKey, b.kyberPreKeySignature,
        )
        assertThrows(IllegalArgumentException::class.java) { long.encode() }
    }
}
