package com.netpopup.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

/**
 * Vérifie que libsignal fonctionne sur la JVM et documente ce que la version retenue
 * impose pour l'échange de contact de mesh-10 (bundle avec ou sans pré-clé Kyber).
 */
class SignalSessionTest {
    private class Peer(name: String) {
        val identity = LocalIdentity.loadOrCreate(InMemoryIdentityVault())
        val store = InMemorySignalProtocolStore(identity.keyPair, identity.registrationId)
        val address = SignalProtocolAddress(name, 1)
        val signedPreKey = Curve.generateKeyPair()
        val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)

        init {
            val spkSig = identity.keyPair.privateKey.calculateSignature(signedPreKey.publicKey.serialize())
            store.storeSignedPreKey(1, SignedPreKeyRecord(1, 0, signedPreKey, spkSig))
            val kSig = identity.keyPair.privateKey.calculateSignature(kyber.publicKey.serialize())
            store.storeKyberPreKey(1, KyberPreKeyRecord(1, 0, kyber, kSig))
        }

        fun bundle(withKyber: Boolean = true) = PreKeyBundle(
            identity.registrationId, 1,
            -1, null, // pas de pré-clé à usage unique en v0
            1, signedPreKey.publicKey,
            identity.keyPair.privateKey.calculateSignature(signedPreKey.publicKey.serialize()),
            identity.publicKey,
            if (withKyber) 1 else -1,
            if (withKyber) kyber.publicKey else null,
            if (withKyber) identity.keyPair.privateKey.calculateSignature(kyber.publicKey.serialize()) else null,
        )
    }

    @Test fun x3dhSessionAndDoubleRatchetRoundTrip() {
        val alice = Peer("alice")
        val bob = Peer("bob")
        SessionBuilder(alice.store, bob.address).process(bob.bundle())

        val first = SessionCipher(alice.store, bob.address).encrypt("salut Bob".toByteArray())
        assertEquals(CiphertextMessage.PREKEY_TYPE, first.type)
        val bobCipher = SessionCipher(bob.store, alice.address)
        assertArrayEquals("salut Bob".toByteArray(), bobCipher.decrypt(PreKeySignalMessage(first.serialize())))

        val reply = bobCipher.encrypt("salut Alice".toByteArray())
        assertEquals(CiphertextMessage.WHISPER_TYPE, reply.type)
        val aliceCipher = SessionCipher(alice.store, bob.address)
        assertArrayEquals("salut Alice".toByteArray(), aliceCipher.decrypt(SignalMessage(reply.serialize())))
    }

    @Test fun outOfOrderMessagesAreDecrypted() {
        val alice = Peer("alice")
        val bob = Peer("bob")
        SessionBuilder(alice.store, bob.address).process(bob.bundle())
        val aliceCipher = SessionCipher(alice.store, bob.address)
        val bobCipher = SessionCipher(bob.store, alice.address)
        val m1 = aliceCipher.encrypt("un".toByteArray())
        bobCipher.decrypt(PreKeySignalMessage(m1.serialize()))
        bobCipher.encrypt("ack".toByteArray()).let { aliceCipher.decrypt(SignalMessage(it.serialize())) }
        val m2 = aliceCipher.encrypt("deux".toByteArray())
        val m3 = aliceCipher.encrypt("trois".toByteArray())
        assertArrayEquals("trois".toByteArray(), bobCipher.decrypt(SignalMessage(m3.serialize())))
        assertArrayEquals("deux".toByteArray(), bobCipher.decrypt(SignalMessage(m2.serialize())))
    }

    /**
     * Constat pour mesh-10 : libsignal 0.72 accepte encore un bundle sans pré-clé Kyber
     * (X3DH classique). PQXDH reste donc un choix, pas une obligation de la bibliothèque.
     */
    @Test fun x3dhWithoutKyberPreKeyStillWorks() {
        val alice = Peer("alice")
        val bob = Peer("bob")
        SessionBuilder(alice.store, bob.address).process(bob.bundle(withKyber = false))
        val first = SessionCipher(alice.store, bob.address).encrypt("sans kyber".toByteArray())
        assertArrayEquals(
            "sans kyber".toByteArray(),
            SessionCipher(bob.store, alice.address).decrypt(PreKeySignalMessage(first.serialize())),
        )
    }

    @Test fun kyberPreKeyAddsAboutOnePointSixKilobytesToTheBundle() {
        val size = Peer("bob").kyber.publicKey.serialize().size
        assertEquals(true, size in 1500..1700)
    }
}
