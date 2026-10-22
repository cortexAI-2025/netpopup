package com.netpopup.store.sql

import com.netpopup.crypto.ContactBundle
import com.netpopup.crypto.LocalIdentity
import com.netpopup.crypto.PairwiseSession
import com.netpopup.crypto.PreKeyManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.state.IdentityKeyStore
import java.io.File
import java.util.UUID

/** Les états libsignal tiennent sur disque : on « redémarre » en rouvrant le fichier. */
class SqlSignalStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val opened = ArrayList<JdbcSqlDatabase>()

    @After fun close() = opened.forEach { runCatching { it.close() } }

    /** Un appareil dont tout l'état est dans [file] ; [boot] le reconstruit comme après un redémarrage. */
    private inner class Device(val name: String) {
        val file = File(tmp.root, "$name.db")
        var now = 1_700_000_000_000L
        lateinit var db: JdbcSqlDatabase
        lateinit var identity: LocalIdentity
        lateinit var session: PairwiseSession
        lateinit var store: SqlSignalProtocolStore
        lateinit var preKeys: PreKeyManager

        init { boot() }

        fun boot() {
            if (::db.isInitialized) db.close()
            db = JdbcSqlDatabase.open(file).also { opened += it; Schema.migrate(it) }
            identity = LocalIdentity.loadOrCreate(SqlIdentityVault(db))
            store = SqlSignalProtocolStore(db, identity.keyPair, identity.registrationId)
            session = PairwiseSession(store)
            preKeys = PreKeyManager(store, identity) { now }
        }

        fun bundle() = preKeys.currentBundle(name)
    }

    private fun send(from: Device, to: Device, text: String) = from.session.encrypt(to.identity.publicKey, text.toByteArray())
    private fun read(at: Device, from: Device, wire: ByteArray) = String(at.session.decrypt(wire, from.identity.publicKey).plaintext)

    @Test fun identityIsStableAcrossRestarts() {
        val a = Device("a")
        val before = a.identity.publicKey.serialize().toList()
        val reg = a.identity.registrationId
        a.boot()
        assertEquals(before, a.identity.publicKey.serialize().toList())
        assertEquals(reg, a.identity.registrationId)
    }

    @Test fun conversationContinuesAcrossRestartsOfBothSides() {
        val alice = Device("alice")
        val bob = Device("bob")
        alice.session.start(ContactBundle.decode(bob.bundle().encode()))
        assertEquals("un", read(bob, alice, send(alice, bob, "un")))
        assertEquals("deux", read(alice, bob, send(bob, alice, "deux")))

        alice.boot(); bob.boot() // redémarrage des deux téléphones : sessions relues depuis la base

        assertEquals("trois", read(bob, alice, send(alice, bob, "trois")))
        assertEquals("quatre", read(alice, bob, send(bob, alice, "quatre")))
    }

    @Test fun messageInFlightIsDecryptedAfterTheReceiverRestarts() {
        val alice = Device("alice")
        val bob = Device("bob")
        alice.session.start(ContactBundle.decode(bob.bundle().encode()))
        val wire = send(alice, bob, "en vol") // premier message, pré-clés requises
        bob.boot()
        assertEquals("en vol", read(bob, alice, wire))
    }

    @Test fun oneBundleServesSeveralContactsWithPersistentKyberKey() {
        val bob = Device("bob")
        val bundle = ContactBundle.decode(bob.bundle().encode())
        for (name in listOf("c1", "c2", "c3")) {
            val c = Device(name)
            c.session.start(bundle)
            assertEquals("de $name", read(bob, c, send(c, bob, "de $name")))
            bob.boot() // et la clé Kyber est toujours là au redémarrage
        }
    }

    @Test fun senderKeysSurviveARestart() {
        val alice = Device("alice")
        val bob = Device("bob")
        val room = UUID.randomUUID()
        val aliceAddress = PairwiseSession.addressOf(alice.identity.publicKey)
        val skdm = GroupSessionBuilder(alice.store).create(aliceAddress, room)
        GroupSessionBuilder(bob.store).process(aliceAddress, skdm)
        val first = GroupCipher(alice.store, aliceAddress).encrypt(room, "salle".toByteArray()).serialize()
        assertEquals("salle", String(GroupCipher(bob.store, aliceAddress).decrypt(first)))

        alice.boot(); bob.boot()
        val second = GroupCipher(alice.store, aliceAddress).encrypt(room, "toujours".toByteArray()).serialize()
        assertEquals("toujours", String(GroupCipher(bob.store, aliceAddress).decrypt(second)))
    }

    @Test fun changedIdentityKeyIsNotTrustedSilently() {
        val alice = Device("alice")
        val bob = Device("bob")
        val impostor = Device("impostor")
        val address = SignalProtocolAddress("bob", 1)
        alice.store.saveIdentity(address, bob.identity.publicKey)
        assertTrue(alice.store.isTrustedIdentity(address, bob.identity.publicKey, IdentityKeyStore.Direction.SENDING))
        assertFalse(alice.store.isTrustedIdentity(address, impostor.identity.publicKey, IdentityKeyStore.Direction.RECEIVING))
        assertEquals(IdentityKeyStore.IdentityChange.REPLACED_EXISTING, alice.store.saveIdentity(address, impostor.identity.publicKey))
        alice.boot()
        assertEquals(impostor.identity.publicKey, alice.store.getIdentity(address))
    }

    @Test fun signedPreKeyRotationIsPersisted() {
        val bob = Device("bob")
        val first = bob.bundle()
        bob.now += 8L * 24 * 3600 * 1000
        val second = bob.bundle()
        bob.boot()
        bob.now += 8L * 24 * 3600 * 1000 - 1
        val ids = bob.store.loadSignedPreKeys().map { it.id }
        assertEquals(listOf(first.signedPreKeyId, second.signedPreKeyId), ids)
    }
}
