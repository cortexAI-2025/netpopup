package com.netpopup.dtn.store

import com.netpopup.dtn.envelope.AckTokens
import com.netpopup.dtn.envelope.Envelope
import com.netpopup.dtn.envelope.EnvelopeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeStoreTest {

    private val t0 = 1_776_000_000L
    private val hour = 3600L
    private var seq = 0

    /** Chaque appel crée une enveloppe distincte (payload différent). */
    private fun envelope(
        createdAt: Long = t0,
        lifetime: Int = 6 * 3600,
        tag: Byte = 1,
        kind: EnvelopeKind = EnvelopeKind.DATA,
        ack: ByteArray = ByteArray(16),
        payloadSize: Int = 40,
        hopCount: Int = 0
    ): Envelope {
        val n = seq++
        val payload = ByteArray(payloadSize).also { it[0] = n.toByte(); it[1] = (n shr 8).toByte() }
        return Envelope(kind, ByteArray(16) { tag }, createdAt, lifetime, hopCount = hopCount, ack = ack, payload = payload)
    }

    // ── Cycle de vie ─────────────────────────────────────────────────────────

    @Test
    fun `stores once, then reports duplicates`() {
        val store = EnvelopeStore()
        val e = envelope()
        assertTrue(store.insert(e, Origin.SELF, t0) is InsertResult.Stored)
        assertEquals(InsertResult.Duplicate, store.insert(e, Origin.RELAY, t0))
        assertEquals(InsertResult.Duplicate, store.insert(e.forRelay(), Origin.RELAY, t0))
        assertEquals(Origin.SELF, store.get(e.id)?.origin)
    }

    @Test
    fun `expired envelope is refused`() {
        val store = EnvelopeStore()
        val e = envelope(lifetime = 3600)
        assertEquals(InsertResult.Expired, store.insert(e, Origin.RELAY, e.expiresAt + 1))
    }

    @Test
    fun `purge removes expired envelopes and tombstones them until TTL plus 3 hours`() {
        val store = EnvelopeStore()
        val e = envelope(lifetime = 3600)
        store.insert(e, Origin.RELAY, t0)

        // Toujours là à createdAt + lifetime + 2 h
        assertEquals(PurgeResult(0, 0), store.purge(t0 + hour + 2 * hour))
        assertTrue(store.contains(e.id))

        // Expirée juste après : supprimée et sous pierre tombale
        assertEquals(PurgeResult(1, 0), store.purge(t0 + 3 * hour + 1))
        assertFalse(store.contains(e.id))
        assertTrue(store.isKnown(e.id))
        assertEquals(InsertResult.Tombstoned, store.insert(e, Origin.RELAY, t0 + 3 * hour + 1))

        // Pierre tombale effacée après createdAt + lifetime + 3 h
        assertEquals(PurgeResult(0, 0), store.purge(t0 + hour + 3 * hour))
        assertEquals(PurgeResult(0, 1), store.purge(t0 + hour + 3 * hour + 1))
        assertFalse(store.isKnown(e.id))
    }

    @Test
    fun `self envelopes also expire`() {
        val store = EnvelopeStore()
        val e = envelope(lifetime = 3600)
        store.insert(e, Origin.SELF, t0)
        store.purge(e.expiresAt + 1)
        assertFalse(store.contains(e.id))
    }

    // ── Acquittements 1:1 ────────────────────────────────────────────────────

    @Test
    fun `ack token removes the matching data envelope and tombstones it`() {
        val store = EnvelopeStore()
        val token = AckTokens.newToken()
        val data = envelope(ack = AckTokens.hash(token))
        val other = envelope()
        store.insert(data, Origin.RELAY, t0)
        store.insert(other, Origin.RELAY, t0)

        assertEquals(data.id, store.applyAck(token))
        assertFalse(store.contains(data.id))
        assertEquals(InsertResult.Tombstoned, store.insert(data, Origin.RELAY, t0))
        assertTrue(store.contains(other.id))
    }

    @Test
    fun `forged or malformed ack token has no effect`() {
        val store = EnvelopeStore()
        val data = envelope(ack = AckTokens.hash(AckTokens.newToken()))
        store.insert(data, Origin.RELAY, t0)

        assertNull(store.applyAck(AckTokens.newToken()))
        assertNull(store.applyAck(ByteArray(3)))
        assertTrue(store.contains(data.id))
    }

    @Test
    fun `ack never removes non-DATA envelopes`() {
        val store = EnvelopeStore()
        val token = AckTokens.newToken()
        val announce = envelope(kind = EnvelopeKind.ANNOUNCE, ack = AckTokens.hash(token))
        store.insert(announce, Origin.RELAY, t0)
        assertNull(store.applyAck(token))
        assertTrue(store.contains(announce.id))
    }

    // ── Plafonds et éviction ─────────────────────────────────────────────────

    @Test
    fun `count cap evicts the relay envelope closest to expiry, never self`() {
        val store = EnvelopeStore(limits = StoreLimits(maxEnvelopes = 3))
        val self = envelope(lifetime = 3600)                  // expire le plus tôt, mais SELF
        val relaySoon = envelope(lifetime = 2 * 3600, tag = 2)
        val relayLate = envelope(lifetime = 5 * 3600, tag = 3)
        store.insert(self, Origin.SELF, t0)
        store.insert(relaySoon, Origin.RELAY, t0)
        store.insert(relayLate, Origin.RELAY, t0)

        val result = store.insert(envelope(tag = 4), Origin.RELAY, t0)
        assertEquals(InsertResult.Stored(listOf(relaySoon.id)), result)
        assertTrue(store.contains(self.id))
        assertTrue(store.contains(relayLate.id))
    }

    @Test
    fun `expired envelopes are evicted before live ones`() {
        val store = EnvelopeStore(limits = StoreLimits(maxEnvelopes = 2))
        val stale = envelope(lifetime = 3600, tag = 2)
        val live = envelope(lifetime = 6 * 3600, tag = 3)
        store.insert(stale, Origin.RELAY, t0)
        store.insert(live, Origin.RELAY, t0)

        val later = stale.expiresAt + 1
        val result = store.insert(envelope(createdAt = later, tag = 4), Origin.RELAY, later)
        assertEquals(InsertResult.Stored(listOf(stale.id)), result)
        assertTrue(store.isKnown(stale.id))   // expirée : pierre tombale
    }

    @Test
    fun `hop count plays no part in eviction`() {
        val store = EnvelopeStore(limits = StoreLimits(maxEnvelopes = 2))
        val farTravelled = envelope(lifetime = 5 * 3600, tag = 2, hopCount = 200)
        val nearExpiry = envelope(lifetime = 2 * 3600, tag = 3, hopCount = 0)
        store.insert(farTravelled, Origin.RELAY, t0)
        store.insert(nearExpiry, Origin.RELAY, t0)

        store.insert(envelope(tag = 4), Origin.RELAY, t0)
        assertTrue(store.contains(farTravelled.id))
        assertFalse(store.contains(nearExpiry.id))
    }

    @Test
    fun `relay is refused when only self envelopes fill the store, without evicting anything`() {
        val store = EnvelopeStore(limits = StoreLimits(maxEnvelopes = 2))
        val a = envelope(tag = 2)
        val b = envelope(tag = 3)
        store.insert(a, Origin.SELF, t0)
        store.insert(b, Origin.SELF, t0)

        assertEquals(InsertResult.Full, store.insert(envelope(tag = 4), Origin.RELAY, t0))
        assertEquals(2, store.stats().envelopes)
    }

    @Test
    fun `a refused relay never costs another envelope its place`() {
        // 1 RELAY évinçable, mais il en faudrait 2 places : refus, et le RELAY reste.
        val store = EnvelopeStore(limits = StoreLimits(maxEnvelopes = 10, maxBytes = 300))
        val self = envelope(payloadSize = 100)               // 164 o
        val relay = envelope(payloadSize = 40, tag = 2)      // 104 o
        store.insert(self, Origin.SELF, t0)
        store.insert(relay, Origin.RELAY, t0)

        assertEquals(InsertResult.Full, store.insert(envelope(payloadSize = 200, tag = 3), Origin.RELAY, t0))
        assertTrue(store.contains(relay.id))
    }

    @Test
    fun `self is always accepted, even beyond the cap`() {
        val store = EnvelopeStore(limits = StoreLimits(maxEnvelopes = 1))
        store.insert(envelope(tag = 2), Origin.SELF, t0)
        assertTrue(store.insert(envelope(tag = 3), Origin.SELF, t0) is InsertResult.Stored)
        assertEquals(2, store.stats().envelopes)
    }

    @Test
    fun `byte cap is enforced`() {
        val store = EnvelopeStore(limits = StoreLimits(maxBytes = 250))
        val first = envelope(payloadSize = 60, lifetime = 2 * 3600)    // 124 o, expire le premier
        val second = envelope(payloadSize = 60, tag = 2, lifetime = 5 * 3600)
        store.insert(first, Origin.RELAY, t0)
        store.insert(second, Origin.RELAY, t0)
        assertEquals(248L, store.stats().bytes)

        val result = store.insert(envelope(payloadSize = 60, tag = 3), Origin.RELAY, t0)
        assertEquals(InsertResult.Stored(listOf(first.id)), result)
        assertTrue(store.stats().bytes <= 250)
    }

    @Test
    fun `per destTag cap only evicts envelopes of the same tag`() {
        val store = EnvelopeStore(limits = StoreLimits(maxPerDestTag = 2))
        val otherTag = envelope(tag = 9, lifetime = 3600)       // expire le plus tôt, autre étiquette
        val sameOld = envelope(tag = 1, lifetime = 2 * 3600)
        val sameNew = envelope(tag = 1, lifetime = 5 * 3600)
        listOf(otherTag, sameOld, sameNew).forEach { store.insert(it, Origin.RELAY, t0) }

        val result = store.insert(envelope(tag = 1), Origin.RELAY, t0)
        assertEquals(InsertResult.Stored(listOf(sameOld.id)), result)
        assertTrue(store.contains(otherTag.id))
    }

    @Test
    fun `per destTag cap refuses a relay when the tag holds only self envelopes`() {
        val store = EnvelopeStore(limits = StoreLimits(maxPerDestTag = 1))
        store.insert(envelope(tag = 1), Origin.SELF, t0)
        assertEquals(InsertResult.Full, store.insert(envelope(tag = 1), Origin.RELAY, t0))
    }

    @Test
    fun `default limits match the architecture document`() {
        val limits = StoreLimits()
        assertEquals(5_000, limits.maxEnvelopes)
        assertEquals(20L * 1024 * 1024, limits.maxBytes)
        assertEquals(1_000, limits.maxPerDestTag)
        assertEquals(3 * 3600L, EnvelopeStore.tombstonePurgeAt(envelope(lifetime = 1)) - (t0 + 1))
    }
}
