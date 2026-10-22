package com.netpopup.dtn.direct

import com.netpopup.crypto.InMemoryIdentityVault
import com.netpopup.crypto.LocalIdentity
import com.netpopup.crypto.PairwiseSession
import com.netpopup.crypto.PreKeyManager
import com.netpopup.dtn.chat.DeliveryState
import com.netpopup.dtn.envelope.AckTokens
import com.netpopup.dtn.envelope.Envelope
import com.netpopup.dtn.envelope.EnvelopeKind
import com.netpopup.dtn.store.EnvelopeStore
import com.netpopup.dtn.store.InsertResult
import com.netpopup.dtn.store.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

class DirectMessengerTest {
    private val t0 = 1_700_000_000L

    private class Device(name: String) {
        var nowMs = 1_700_000_000_000L
        val identity = LocalIdentity.loadOrCreate(InMemoryIdentityVault())
        val signal = InMemorySignalProtocolStore(identity.keyPair, identity.registrationId)
        val session = PairwiseSession(signal)
        val bundle = PreKeyManager(signal, identity) { nowMs }.currentBundle(name)
        val store = EnvelopeStore()
        lateinit var chat: DirectMessenger

        fun connectTo(other: Device, pending: PendingSendStore = NoPendingSendStore) {
            chat = DirectMessenger(identity, other.identity.publicKey, session, store, { nowMs }, pending)
        }
    }

    private fun pair(): Pair<Device, Device> {
        val alice = Device("Alice")
        val bob = Device("Bob")
        alice.session.start(bob.bundle)
        alice.connectTo(bob)
        bob.connectTo(alice)
        return alice to bob
    }

    /** Un contact radio : [to] reçoit tout ce que [from] détient et l'insère comme relais. */
    private fun meet(from: Device, to: Device): List<DirectEvent> {
        val events = ArrayList<DirectEvent>()
        for (stored in from.store.all()) {
            val relayed = stored.envelope.forRelay()
            if (to.store.insert(relayed, Origin.RELAY, to.nowMs / 1000) is InsertResult.Stored) {
                to.chat.handle(relayed)?.let(events::add)
            }
        }
        return events
    }

    @Test fun sendDeliverAcknowledge() {
        val (alice, bob) = pair()
        val sent = alice.chat.send("salut Bob")
        assertEquals(DeliveryState.QUEUED, alice.chat.deliveryState(sent.messageId))

        val atBob = meet(alice, bob)
        val received = atBob.single() as DirectEvent.Received
        assertEquals("salut Bob", received.body)
        // Bob a retiré la DATA livrée et publié un ACK.
        assertTrue(bob.store.all().none { it.envelope.kind == EnvelopeKind.DATA })
        assertEquals(1, bob.store.all().count { it.envelope.kind == EnvelopeKind.ACK })

        val atAlice = meet(bob, alice)
        assertEquals(DirectEvent.Acknowledged(sent.messageId), atAlice.single())
        assertEquals(DeliveryState.ACKNOWLEDGED, alice.chat.deliveryState(sent.messageId))
        // Alice a retiré sa propre DATA acquittée.
        assertTrue(alice.store.all().none { it.envelope.kind == EnvelopeKind.DATA })
    }

    @Test fun relayDropsTheDataWhenTheAckPasses() {
        val (alice, bob) = pair()
        val relay = Device("Relais").also { it.connectTo(alice) }
        val sent = alice.chat.send("par un relais")
        meet(alice, relay)
        assertEquals(1, relay.store.all().count { it.envelope.kind == EnvelopeKind.DATA })

        meet(alice, bob) // Bob reçoit directement et acquitte
        meet(bob, relay) // le relais voit passer l'ACK
        assertTrue(relay.store.all().none { it.envelope.kind == EnvelopeKind.DATA })
        // L'ACK reste stocké pour être relayé jusqu'à sa durée de vie.
        assertEquals(1, relay.store.all().count { it.envelope.kind == EnvelopeKind.ACK })
        // La DATA ne peut pas revenir chez le relais.
        val data = alice.store.all().single { it.envelope.kind == EnvelopeKind.DATA }.envelope
        assertEquals(InsertResult.Tombstoned, relay.store.insert(data.forRelay(), Origin.RELAY, relay.nowMs / 1000))
        // L'ACK atteint enfin Alice par le relais : message acquitté, sa copie de la DATA disparaît.
        assertEquals(DirectEvent.Acknowledged(sent.messageId), meet(relay, alice).single())
        assertTrue(alice.store.all().none { it.envelope.kind == EnvelopeKind.DATA })
    }

    @Test fun ackArrivingBeforeItsDataPreventsTheDataFromBeingStored() {
        val (alice, bob) = pair()
        val relay = Device("Relais").also { it.connectTo(alice) }
        alice.chat.send("rapide")
        val data = alice.store.all().single().envelope
        meet(alice, bob)
        meet(bob, relay) // l'ACK arrive d'abord chez le relais
        assertEquals(InsertResult.Tombstoned, relay.store.insert(data.forRelay(), Origin.RELAY, relay.nowMs / 1000))
        assertTrue(relay.store.all().none { it.envelope.kind == EnvelopeKind.DATA })
    }

    @Test fun forgedAckWithWrongTokenHasNoEffect() {
        val (alice, bob) = pair()
        val sent = alice.chat.send("important")
        val data = alice.store.all().single().envelope
        val forged = Envelope(
            EnvelopeKind.ACK, data.destTag, t0, 3600,
            ack = AckTokens.newToken(), payload = ByteArray(Envelope.MIN_PAYLOAD_SIZE),
        )
        // Chez un relais comme chez l'émetteur : rien n'est supprimé, rien n'est acquitté.
        alice.store.insert(forged, Origin.RELAY, t0)
        assertEquals(1, alice.store.all().count { it.envelope.kind == EnvelopeKind.DATA })
        assertEquals(DirectEvent.Rejected(DirectEvent.Reason.UNKNOWN_ACK_TOKEN), alice.chat.handle(forged))
        assertEquals(DeliveryState.QUEUED, alice.chat.deliveryState(sent.messageId))
        // Un ACK à jeton nul est rejeté comme malformé.
        val zero = Envelope(EnvelopeKind.ACK, data.destTag, t0 + 1, 3600, payload = ByteArray(Envelope.MIN_PAYLOAD_SIZE))
        assertEquals(DirectEvent.Rejected(DirectEvent.Reason.MALFORMED), alice.chat.handle(zero))
        assertNotNull(bob)
    }

    @Test fun dataWithAZeroAckFieldIsNeverMatchedByAnAck() {
        val store = EnvelopeStore()
        val roomData = Envelope(EnvelopeKind.DATA, ByteArray(16) { 1 }, t0, 3600, payload = ByteArray(Envelope.MIN_PAYLOAD_SIZE))
        store.insert(roomData, Origin.RELAY, t0)
        val anyAck = Envelope(EnvelopeKind.ACK, ByteArray(16) { 1 }, t0, 3600, ack = AckTokens.newToken(), payload = ByteArray(Envelope.MIN_PAYLOAD_SIZE))
        store.insert(anyAck, Origin.RELAY, t0)
        assertNull(store.applyAck(ByteArray(16)))
        assertEquals(1, store.all().count { it.envelope.kind == EnvelopeKind.DATA })
    }

    @Test fun ackIsOnlyPublishedAfterAuthenticDecryption() {
        val (alice, bob) = pair()
        alice.chat.send("vrai")
        val real = alice.store.all().single().envelope
        // Un relais altère le payload : il ne s'ouvre pas, aucun ACK n'est émis.
        val payload = real.payload.also { it[30] = (it[30].toInt() xor 1).toByte() }
        val tampered = Envelope(EnvelopeKind.DATA, real.destTag, real.createdAt, real.lifetime, ack = real.ack, payload = payload)
        bob.store.insert(tampered, Origin.RELAY, bob.nowMs / 1000)
        assertEquals(DirectEvent.Rejected(DirectEvent.Reason.UNDECRYPTABLE), bob.chat.handle(tampered))
        assertTrue(bob.store.all().none { it.envelope.kind == EnvelopeKind.ACK })
        // Un en-tête altéré (ack remplacé) invalide aussi l'AAD.
        val swapped = Envelope(EnvelopeKind.DATA, real.destTag, real.createdAt, real.lifetime, ack = AckTokens.hash(AckTokens.newToken()), payload = real.payload)
        assertEquals(DirectEvent.Rejected(DirectEvent.Reason.UNDECRYPTABLE), bob.chat.handle(swapped))
    }

    @Test fun relayedStateAndOwnEnvelopesLoopingBack() {
        val (alice, bob) = pair()
        val sent = alice.chat.send("x")
        alice.chat.markRelayed(sent.envelopeId)
        assertEquals(DeliveryState.RELAYED, alice.chat.deliveryState(sent.messageId))
        val own = alice.store.all().single().envelope
        assertEquals(DirectEvent.Rejected(DirectEvent.Reason.OWN_ENVELOPE), alice.chat.handle(own))
        meet(alice, bob); meet(bob, alice)
        assertEquals(DeliveryState.ACKNOWLEDGED, alice.chat.deliveryState(sent.messageId))
        // Un second ACK pour le même message est ignoré.
        val ack = bob.store.all().single { it.envelope.kind == EnvelopeKind.ACK }.envelope
        assertEquals(DirectEvent.Rejected(DirectEvent.Reason.UNKNOWN_ACK_TOKEN), alice.chat.handle(ack.forRelay()))
    }

    @Test fun conversationWithTwoMessagesAndTheirAcks() {
        val (alice, bob) = pair()
        val m1 = alice.chat.send("un")
        val m2 = alice.chat.send("deux")
        val got = meet(alice, bob).filterIsInstance<DirectEvent.Received>().map { it.body }.toSet()
        assertEquals(setOf("un", "deux"), got)
        meet(bob, alice)
        assertEquals(DeliveryState.ACKNOWLEDGED, alice.chat.deliveryState(m1.messageId))
        assertEquals(DeliveryState.ACKNOWLEDGED, alice.chat.deliveryState(m2.messageId))
        // Bob répond, et Alice reçoit.
        val reply = bob.chat.send("bien reçu")
        val atAlice = meet(bob, alice).filterIsInstance<DirectEvent.Received>().single()
        assertEquals("bien reçu", atAlice.body)
        meet(alice, bob)
        assertEquals(DeliveryState.ACKNOWLEDGED, bob.chat.deliveryState(reply.messageId))
    }

    @Test fun otherConversationsAreIgnored() {
        val (alice, bob) = pair()
        val carol = Device("Carol")
        carol.connectTo(alice)
        alice.chat.send("pour Bob")
        val env = alice.store.all().single().envelope
        assertNull(carol.chat.handle(env))
    }

    @Test fun longestMessageStillFitsInOneEnvelope() {
        val (alice, _) = pair()
        // Pire cas : 500 caractères de 3 octets UTF-8 (1 500 octets), premier message inclus (échange PQXDH).
        val worst = "\u4E2D".repeat(500)
        alice.chat.send(worst)
        val size = alice.store.all().single().envelope.size
        assertTrue("enveloppe de $size octets", size <= Envelope.MAX_SIZE)
    }

    // ── Messages de contrôle et redémarrage ──────────────────────────────────

    private class MapPendingStore : PendingSendStore {
        val rows = HashMap<String, PendingSend>()
        override fun load(peer: String) = rows.values.toList()
        override fun save(peer: String, send: PendingSend) { rows[send.tokenHash] = send }
    }

    @Test fun controlMessagesAreDeliveredAndAcknowledgedButNeverShownAsText() {
        val (alice, bob) = pair()
        val payload = byteArrayOf(2, 9, 8, 7)
        val sent = alice.chat.sendControl(payload)
        val event = meet(alice, bob).single() as DirectEvent.ControlReceived
        assertTrue(payload.contentEquals(event.payload))
        assertEquals(DirectEvent.Acknowledged(sent.messageId), meet(bob, alice).single())
        // Le relais d'état ne renvoie pas d'identifiant de message pour un contrôle.
        val second = alice.chat.sendControl(byteArrayOf(1))
        assertNull(alice.chat.markRelayed(second.envelopeId))
    }

    @Test fun pendingAcknowledgementsSurviveARestartOfTheSender() {
        val alice = Device("Alice"); val bob = Device("Bob")
        alice.session.start(bob.bundle)
        val persisted = MapPendingStore()
        alice.connectTo(bob, persisted); bob.connectTo(alice)
        val sent = alice.chat.send("avant le redémarrage")
        assertNotNull(alice.chat.markRelayed(sent.envelopeId))
        meet(alice, bob)

        // Redémarrage : nouvel objet DirectMessenger, mêmes stockages.
        alice.connectTo(bob, persisted)
        assertEquals(DeliveryState.RELAYED, alice.chat.deliveryState(sent.messageId))
        assertTrue(alice.chat.owns(sent.envelopeId))
        assertEquals(DirectEvent.Acknowledged(sent.messageId), meet(bob, alice).single())
        assertEquals(DeliveryState.ACKNOWLEDGED, alice.chat.deliveryState(sent.messageId))
    }
}
