package com.netpopup.dtn.sync

import com.netpopup.dtn.envelope.AckTokens
import com.netpopup.dtn.envelope.Envelope
import com.netpopup.dtn.envelope.EnvelopeKind
import com.netpopup.dtn.store.EnvelopeStore
import com.netpopup.dtn.store.InsertResult
import com.netpopup.dtn.store.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshSimulatorTest {

    private var seq = 0

    /**
     * Jeton fixe : avec un jeton aléatoire, l'ID de l'ACK change à chaque exécution et, une fois
     * sur ~100, le filtre de Bloom du pair le retient à tort (l'ACK attend alors le contact suivant).
     * Le comportement est voulu, mais un test ne doit pas en dépendre du hasard.
     */
    private fun token(n: Int) = ByteArray(16) { (n * 31 + it).toByte() }

    private fun MeshSimulator.envelope(
        tag: Byte = 1,
        kind: EnvelopeKind = EnvelopeKind.DATA,
        lifetime: Int = 6 * 3600,
        ack: ByteArray = ByteArray(16),
        createdAt: Long = nowSeconds
    ): Envelope {
        val n = seq++
        return Envelope(kind, ByteArray(16) { tag }, createdAt, lifetime, ack = ack,
            payload = ByteArray(40).also { it[0] = n.toByte(); it[1] = (n shr 8).toByte() })
    }

    // ── Deux appareils ───────────────────────────────────────────────────────

    @Test
    fun `two devices converge on the union of their envelopes`() {
        val sim = MeshSimulator()
        val a = sim.node("A"); val b = sim.node("B")
        val fromA = (1..5).map { sim.envelope(tag = 1) }.onEach { sim.create(a, it) }
        val fromB = (1..4).map { sim.envelope(tag = 2) }.onEach { sim.create(b, it) }

        sim.meet(a, b)

        assertTrue(fromA.all { b.has(it.id) })
        assertTrue(fromB.all { a.has(it.id) })
        assertEquals(9, a.store.stats().envelopes)
        assertEquals(9, b.store.stats().envelopes)
    }

    @Test
    fun `a second meeting sends nothing`() {
        val sim = MeshSimulator()
        val a = sim.node("A"); val b = sim.node("B")
        repeat(5) { sim.create(a, sim.envelope()) }
        sim.meet(a, b)
        val again = sim.meet(a, b)
        assertEquals(0, again.sentByA)
        assertEquals(0, again.sentByB)
    }

    @Test
    fun `relayed envelope arrives with hopCount plus one and keeps its id`() {
        val sim = MeshSimulator()
        val a = sim.node("A"); val b = sim.node("B")
        val e = sim.envelope().also { sim.create(a, it) }
        sim.meet(a, b)
        val atB = b.store.get(e.id)!!
        assertEquals(1, atB.envelope.hopCount)
        assertEquals(Origin.RELAY, atB.origin)
        assertEquals(Origin.SELF, a.store.get(e.id)!!.origin)
    }

    // ── Store-carry-and-forward ──────────────────────────────────────────────

    @Test
    fun `a message crosses devices that never meet the sender`() {
        val sim = MeshSimulator()
        val a = sim.node("A"); val b = sim.node("B"); val c = sim.node("C")
        val e = sim.envelope().also { sim.create(a, it) }

        sim.meet(a, b); sim.advance(3600)     // B porte le message
        assertFalse(c.has(e.id))
        sim.meet(b, c)

        assertTrue(c.has(e.id))
        assertEquals(2, c.store.get(e.id)!!.envelope.hopCount)
    }

    @Test
    fun `a message does not cross a device that has already seen it`() {
        val sim = MeshSimulator()
        val a = sim.node("A"); val b = sim.node("B")
        val e = sim.envelope().also { sim.create(a, it) }
        sim.meet(a, b)
        // B rencontre A à nouveau : A connaît l'enveloppe, B ne la lui renvoie pas
        assertEquals(0, sim.meet(b, a).sentByB)
        assertEquals(listOf(e.id), a.store.all().map { it.id })
    }

    @Test
    fun `expired envelopes are not sent`() {
        val sim = MeshSimulator()
        val a = sim.node("A"); val b = sim.node("B")
        val old = sim.envelope(lifetime = 3600).also { sim.create(a, it) }
        sim.advance(3 * 3600L + 1)      // TTL + 2 h dépassés
        val result = sim.meet(a, b)
        assertEquals(0, result.sentByA)
        assertFalse(b.has(old.id))
    }

    @Test
    fun `tombstoned envelopes are not accepted again`() {
        val sim = MeshSimulator()
        val a = sim.node("A"); val b = sim.node("B")
        val token = token(1)
        val data = sim.envelope(ack = AckTokens.hash(token)).also { sim.create(a, it) }
        a.store.applyAck(token)        // A a vu l'acquittement : pierre tombale
        sim.meet(a, b)
        assertFalse(b.has(data.id))
        assertTrue(a.store.isKnown(data.id))
    }

    // ── Acquittements ────────────────────────────────────────────────────────

    @Test
    fun `an ack deletes the data envelope on every relay it crosses`() {
        val sim = MeshSimulator()
        val sender = sim.node("sender"); val relay = sim.node("relay"); val dest = sim.node("dest")
        val token = token(2)
        val data = sim.envelope(ack = AckTokens.hash(token)).also { sim.create(sender, it) }

        sim.meet(sender, relay)
        sim.meet(relay, dest)
        assertTrue(dest.has(data.id))

        // Le destinataire acquitte : ACK dont le champ `ack` porte le jeton en clair
        val ack = sim.envelope(kind = EnvelopeKind.ACK, ack = token)
        sim.create(dest, ack)
        sim.meet(dest, relay)
        assertFalse("le relais a supprimé la DATA", relay.has(data.id))
        assertTrue(relay.has(ack.id))

        sim.meet(relay, sender)
        assertFalse("l'émetteur aussi", sender.has(data.id))
    }

    @Test
    fun `acks go first, then own envelopes, then newest`() {
        val sim = MeshSimulator(burst = 1, perSecond = 0.0)   // une seule enveloppe peut partir
        val a = sim.node("A"); val b = sim.node("B")
        sim.create(a, sim.envelope(tag = 1))
        val ack = sim.envelope(kind = EnvelopeKind.ACK, tag = 2, ack = token(3))
        a.store.insert(ack, Origin.RELAY, sim.nowSeconds)    // un ACK relayé passe avant un SELF

        sim.meet(a, b, contactSeconds = 5)
        assertEquals(listOf(ack.id), b.received.map { it.id })
    }

    // ── Débit ────────────────────────────────────────────────────────────────

    @Test
    fun `a contact moves a burst of 20 then one envelope per second`() {
        val sim = MeshSimulator()
        val a = sim.node("A"); val b = sim.node("B")
        repeat(100) { sim.create(a, sim.envelope(tag = (it % 5).toByte())) }

        // 10 s de contact : 20 de rafale + 10 secondes à 1/s
        assertEquals(30, sim.meet(a, b, contactSeconds = 10).sentByA)

        // Nouvelle session = nouveau seau (les pairs sont anonymes, il ne peut pas durer d'une
        // connexion à l'autre). Il reste 70 enveloppes ; le filtre en retient à tort ~1 %.
        val second = sim.meet(a, b, contactSeconds = 60).sentByA
        assertTrue("sent=$second", second in 64..70)
        assertEquals(second + 30, b.store.stats().envelopes)
    }

    @Test
    fun `a peer pushing faster than the allowed rate is throttled`() {
        val store = EnvelopeStore()
        val sim = MeshSimulator()
        val envelopes = (1..30).map { sim.envelope() }
        val session = SyncSession(store, send = {}, burst = 20, perSecond = 1.0)
        val now = sim.nowSeconds * 1000
        envelopes.forEach { session.onFrame(FrameCodec.encodeEnvelope(com.netpopup.dtn.envelope.EnvelopeCodec.encode(it)), now) }
        assertEquals(20, session.stats.accepted)
        assertEquals(10, session.stats.throttled)
    }

    // ── Trames hostiles ──────────────────────────────────────────────────────

    @Test
    fun `garbage and invalid envelopes are counted, never stored, never thrown`() {
        val store = EnvelopeStore()
        val session = SyncSession(store, send = {})
        val sim = MeshSimulator()
        val now = sim.nowSeconds * 1000

        session.onFrame(ByteArray(0), now)
        session.onFrame(byteArrayOf(0x7F, 1, 2), now)
        session.onFrame(FrameCodec.encodeEnvelope(ByteArray(10)), now)              // enveloppe trop courte
        val forged = com.netpopup.dtn.envelope.EnvelopeCodec.encode(sim.envelope()).also { it[2] = (it[2].toInt() xor 1).toByte() }
        session.onFrame(FrameCodec.encodeEnvelope(forged), now)                       // id falsifié

        assertEquals(2, session.stats.malformed)
        assertEquals(2, session.stats.rejected)
        assertEquals(0, store.stats().envelopes)
    }

    @Test
    fun `a second inventory in the same session is ignored`() {
        val sim = MeshSimulator()
        val sent = mutableListOf<ByteArray>()
        val store = EnvelopeStore().also { it.insert(sim.envelope(), Origin.SELF, sim.nowSeconds) }
        val session = SyncSession(store, send = { sent += it })
        val empty = FrameCodec.encodeInventory(BloomFilter.create(1, 0.01, ByteArray(16)))
        session.onFrame(empty, sim.nowSeconds * 1000)
        assertEquals(1, session.pending)
        session.onFrame(empty, sim.nowSeconds * 1000)
        assertEquals(1, session.pending)
        assertEquals(1, session.stats.rejected)
    }

    // ── Réseau entier ────────────────────────────────────────────────────────

    @Test
    fun `in a random network every message reaches every device`() {
        val sim = MeshSimulator(seed = 42)
        val nodes = (1..10).map { sim.node("N$it") }
        val messages = nodes.take(3).map { n -> sim.envelope(tag = n.name.length.toByte()).also { sim.create(n, it) } }

        sim.randomMeetings(count = 120, contactSeconds = 30, gapSeconds = 60)

        messages.forEach { m -> assertEquals("holders of ${m.id}", 10, sim.holders(m.id).size) }
    }

    @Test
    fun `without meetings nothing moves, and a single meeting reaches one hop only`() {
        val sim = MeshSimulator()
        val nodes = (1..4).map { sim.node("N$it") }
        val e = sim.envelope().also { sim.create(nodes[0], it) }
        assertEquals(1, sim.holders(e.id).size)
        sim.meet(nodes[0], nodes[1])
        assertEquals(2, sim.holders(e.id).size)
        assertEquals(InsertResult.Duplicate, nodes[1].store.insert(e, Origin.RELAY, sim.nowSeconds))
    }
}
