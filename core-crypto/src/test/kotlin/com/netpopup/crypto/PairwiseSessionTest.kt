package com.netpopup.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairwiseSessionTest {
    private fun pair(kyber: Boolean = true): Pair<TestDevice, TestDevice> {
        val alice = TestDevice("Alice", kyber)
        val bob = TestDevice("Bob", kyber)
        alice.session.start(ContactBundle.decode(bob.bundle().encode()))
        return alice to bob
    }

    private fun send(from: TestDevice, to: TestDevice, text: String) =
        from.session.encrypt(to.identity.publicKey, text.toByteArray())

    @Test fun pqxdhAndClassicBothRoundTrip() {
        for (kyber in listOf(true, false)) {
            val (alice, bob) = pair(kyber)
            val first = send(alice, bob, "salut")
            val got = bob.session.decrypt(first)
            assertEquals("salut", String(got.plaintext))
            assertArrayEquals(alice.identity.publicKey.serialize(), got.sender.serialize())
            val reply = send(bob, alice, "re")
            assertEquals("re", String(alice.session.decrypt(reply, bob.identity.publicKey).plaintext))
        }
    }

    @Test fun manyMessagesArrivingInReverseOrder() {
        val (alice, bob) = pair()
        bob.session.decrypt(send(alice, bob, "0"))
        alice.session.decrypt(send(bob, alice, "ack"), bob.identity.publicKey)
        val wires = (1..60).map { send(alice, bob, "m$it") }
        for ((i, w) in wires.withIndex().reversed()) {
            assertEquals("m${i + 1}", String(bob.session.decrypt(w, alice.identity.publicKey).plaintext))
        }
    }

    /** Livre un message retardé après [turns] allers-retours ; renvoie son clair, ou null s'il est perdu. */
    private fun lateMessageAfter(turns: Int): String? {
        val (alice, bob) = pair()
        bob.session.decrypt(send(alice, bob, "0"))
        alice.session.decrypt(send(bob, alice, "ack"), bob.identity.publicKey)
        val late = send(alice, bob, "retardataire")
        repeat(turns) {
            bob.session.decrypt(send(alice, bob, "a$it"), alice.identity.publicKey)
            alice.session.decrypt(send(bob, alice, "b$it"), bob.identity.publicKey)
        }
        return runCatching { String(bob.session.decrypt(late, alice.identity.publicKey).plaintext) }.getOrNull()
    }

    @Test fun lateMessageSurvivesAFewRatchetTurns() {
        assertEquals("retardataire", lateMessageAfter(3))
    }

    /**
     * Limite documentée de libsignal : seules les dernières chaînes de réception sont gardées.
     * Un message retardé pendant que la conversation fait beaucoup d'allers-retours est perdu.
     */
    @Test fun lateMessageIsLostAfterTooManyRatchetTurns() {
        val survivesUpTo = (1..12).takeWhile { lateMessageAfter(it) != null }.lastOrNull() ?: 0
        println("limite mesurée : message retardé lisible jusqu'à $survivesUpTo allers-retours")
        assertTrue("limite inattendue : $survivesUpTo", survivesUpTo in 3..8)
        assertEquals(null, lateMessageAfter(12))
    }

    @Test fun replayedAndTamperedMessagesAreRejected() {
        val (alice, bob) = pair()
        val first = send(alice, bob, "x")
        bob.session.decrypt(first)
        assertThrows(PairwiseDecryptionException::class.java) { bob.session.decrypt(first) }
        alice.session.decrypt(send(bob, alice, "ack"), bob.identity.publicKey)
        val next = send(alice, bob, "y")
        val bad = next.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        assertThrows(PairwiseDecryptionException::class.java) { bob.session.decrypt(bad, alice.identity.publicKey) }
        assertThrows(PairwiseDecryptionException::class.java) { bob.session.decrypt(ByteArray(0)) }
        assertThrows(PairwiseDecryptionException::class.java) { bob.session.decrypt(byteArrayOf(9, 1, 2)) }
        // Un message de suite sans expéditeur connu est refusé plutôt que deviné.
        assertThrows(PairwiseDecryptionException::class.java) { bob.session.decrypt(next) }
    }

    @Test fun thirdPartyCannotReadOrInjectIntoASession() {
        val (alice, bob) = pair()
        val carol = TestDevice("Carol")
        val wire = send(alice, bob, "secret")
        assertThrows(PairwiseDecryptionException::class.java) { carol.session.decrypt(wire) }
    }

    @Test fun pairKeysAreSymmetricAndPerPair() {
        val (alice, bob) = pair()
        val carol = TestDevice("Carol")
        val ab = PairwiseSession.pairKeys(alice.identity.keyPair, bob.identity.publicKey)
        val ba = PairwiseSession.pairKeys(bob.identity.keyPair, alice.identity.publicKey)
        assertArrayEquals(ab.tagKey, ba.tagKey)
        assertArrayEquals(ab.envKey, ba.envKey)
        val ac = PairwiseSession.pairKeys(alice.identity.keyPair, carol.identity.publicKey)
        assertNotEquals(ab.tagKey.toList(), ac.tagKey.toList())
    }

    @Test fun pairKeysFeedTheOuterLayerEndToEnd() {
        val (alice, bob) = pair()
        val keysA = PairwiseSession.pairKeys(alice.identity.keyPair, bob.identity.publicKey)
        val keysB = PairwiseSession.pairKeys(bob.identity.keyPair, alice.identity.publicKey)
        val aad = ByteArray(47) { 3 }
        val inner = send(alice, bob, "bout en bout")
        val onWire = OuterLayer.seal(keysA.envKey, aad, inner)
        val opened = OuterLayer.open(keysB.envKey, aad, onWire)!!
        assertEquals("bout en bout", String(bob.session.decrypt(opened).plaintext))
    }

    @Test fun safetyNumberIsSymmetricSixtyDigitsAndDetectsMitm() {
        val (alice, bob) = pair()
        val mallory = TestDevice("Mallory")
        val a = PairwiseSession.safetyNumber(alice.identity.publicKey, bob.identity.publicKey)
        val b = PairwiseSession.safetyNumber(bob.identity.publicKey, alice.identity.publicKey)
        assertEquals(a, b)
        assertEquals(60, a.replace(" ", "").length)
        assertTrue(a.replace(" ", "").all { it.isDigit() })
        assertNotEquals(a, PairwiseSession.safetyNumber(alice.identity.publicKey, mallory.identity.publicKey))
    }

    @Test fun contactBookKeepsTrustAndIdentifiesByKeyNotName() {
        val book = ContactBook()
        val bob = TestDevice("Bob")
        val fakeBob = TestDevice("Bob")
        book.addFromAnnounce(bob.bundle())
        book.addFromAnnounce(fakeBob.bundle())
        assertEquals(2, book.all().size)
        assertEquals(ContactTrust.ANNOUNCED, book.find(bob.identity.publicKey)!!.trust)
        book.addFromQr(bob.bundle())
        assertEquals(ContactTrust.VERIFIED, book.find(bob.identity.publicKey)!!.trust)
        book.addFromAnnounce(bob.bundle()) // une annonce ne rétrograde pas
        assertEquals(ContactTrust.VERIFIED, book.find(bob.identity.publicKey)!!.trust)
        book.markVerified(fakeBob.identity.publicKey)
        assertEquals(ContactTrust.VERIFIED, book.find(fakeBob.identity.publicKey)!!.trust)
    }
}
