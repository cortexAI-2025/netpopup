package com.netpopup.dtn.sync

import com.netpopup.dtn.envelope.Envelope
import com.netpopup.dtn.envelope.EnvelopeKind
import com.netpopup.dtn.store.EnvelopeStore
import com.netpopup.dtn.store.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncOfferTest {
    private val now = 1_700_000_000_000L

    private fun envelope(n: Int) = Envelope(
        EnvelopeKind.DATA, ByteArray(16) { 1 }, now / 1000, 3600,
        payload = ByteArray(40).also { it[0] = n.toByte(); it[1] = 7 },
    )

    private class Side(val store: EnvelopeStore = EnvelopeStore()) {
        val outbox = ArrayList<ByteArray>()
        val session = SyncSession(store, send = { outbox += it })
    }

    /** Relaie toutes les trames en attente d'un côté vers l'autre, jusqu'à silence. */
    private fun exchange(a: Side, b: Side) {
        do {
            val fromA = a.outbox.toList().also { a.outbox.clear() }
            val fromB = b.outbox.toList().also { b.outbox.clear() }
            fromA.forEach { b.session.onFrame(it, now) }
            fromB.forEach { a.session.onFrame(it, now) }
        } while (a.outbox.isNotEmpty() || b.outbox.isNotEmpty())
    }

    private fun connected(): Pair<Side, Side> {
        val a = Side(); val b = Side()
        a.session.start(); b.session.start()
        exchange(a, b)
        assertTrue(a.session.peerInventoryReceived && b.session.peerInventoryReceived)
        return a to b
    }

    @Test fun envelopeCreatedDuringTheConnectionReachesThePeer() {
        val (a, b) = connected()
        val e = envelope(1)
        a.store.insert(e, Origin.SELF, now / 1000)
        a.session.offer(e)
        a.session.pump(now); exchange(a, b)
        assertTrue(b.store.contains(e.id))
    }

    @Test fun offerBeforeTheInventoryDoesNothing() {
        val a = Side(); val e = envelope(2)
        a.store.insert(e, Origin.SELF, now / 1000)
        a.session.offer(e)
        assertEquals(0, a.session.pending)
    }

    @Test fun anEnvelopeThePeerAlreadyHasIsNotSentAgain() {
        val (a, b) = connected()
        val e = envelope(3)
        a.store.insert(e, Origin.SELF, now / 1000)
        a.session.offer(e); a.session.pump(now); exchange(a, b)
        val sentBefore = a.session.stats.sent
        a.session.offer(e) // proposée une seconde fois
        a.session.pump(now)
        assertEquals(sentBefore, a.session.stats.sent)
    }

    @Test fun anEnvelopeReceivedFromThePeerIsNotEchoedBack() {
        val (a, b) = connected()
        val e = envelope(4)
        b.store.insert(e, Origin.SELF, now / 1000)
        b.session.offer(e); b.session.pump(now); exchange(a, b)
        assertTrue(a.store.contains(e.id))
        a.session.offer(a.store.get(e.id)!!.envelope) // le moteur la propose à tous ses liens, y compris celui d'où elle vient
        assertFalse(a.session.pending > 0)
    }
}
