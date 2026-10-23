package com.netpopup.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PreKeyManagerTest {
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test fun createsOnceAndKeepsItForAWeek() {
        val bob = TestDevice("Bob")
        val first = bob.bundle()
        bob.now += 6 * day
        assertEquals(first.signedPreKeyId, bob.bundle().signedPreKeyId)
        assertEquals(1, bob.store.loadSignedPreKeys().size)
    }

    @Test fun rotatesAfterSevenDaysAndKeepsThePreviousFor26Hours() {
        val bob = TestDevice("Bob")
        val first = bob.bundle()
        bob.now += 7 * day
        val second = bob.bundle()
        assertNotEquals(first.signedPreKeyId, second.signedPreKeyId)
        assertEquals(2, bob.store.loadSignedPreKeys().size)

        bob.now += 25 * hour
        bob.preKeys.rotateIfNeeded()
        assertEquals(2, bob.store.loadSignedPreKeys().size)

        bob.now += 1 * hour
        bob.preKeys.rotateIfNeeded()
        assertEquals(listOf(second.signedPreKeyId), bob.store.loadSignedPreKeys().map { it.id })
    }

    @Test fun firstMessageSentToTheOldBundleWithin26HoursIsStillReadable() {
        val alice = TestDevice("Alice")
        val bob = TestDevice("Bob")
        val oldBundle = bob.bundle()
        bob.now += 7 * day
        bob.bundle() // rotation
        alice.session.start(oldBundle)
        val wire = alice.session.encrypt(oldBundle.identityKey, "en vol".toByteArray())
        bob.now += 20 * hour
        bob.preKeys.rotateIfNeeded()
        assertEquals("en vol", String(bob.session.decrypt(wire).plaintext))
    }

    @Test fun firstMessageAfterThePreviousKeyIsPurgedFails() {
        val alice = TestDevice("Alice")
        val bob = TestDevice("Bob")
        val oldBundle = bob.bundle()
        bob.now += 7 * day
        bob.bundle()
        alice.session.start(oldBundle)
        val wire = alice.session.encrypt(oldBundle.identityKey, "trop tard".toByteArray())
        bob.now += 27 * hour
        bob.preKeys.rotateIfNeeded()
        assertThrows(PairwiseDecryptionException::class.java) { bob.session.decrypt(wire) }
    }
}
