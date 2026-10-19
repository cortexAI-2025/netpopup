package com.netpopup.dtn.envelope

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeCodecTest {

    private val now = 1_776_000_000L

    private fun envelope(
        kind: EnvelopeKind = EnvelopeKind.DATA,
        destTag: ByteArray = ByteArray(16) { 0xAA.toByte() },
        createdAt: Long = now,
        lifetime: Int = Envelope.DEFAULT_LIFETIME_SECONDS,
        hopLimit: Int = Envelope.DEFAULT_HOP_LIMIT,
        hopCount: Int = 0,
        ack: ByteArray = ByteArray(16),
        payload: ByteArray = ByteArray(40) { it.toByte() }
    ) = Envelope(kind, destTag, createdAt, lifetime, hopLimit, hopCount, ack, payload)

    private fun decode(frame: ByteArray, at: Long = now) = EnvelopeCodec.decode(frame, at)

    private fun rejected(frame: ByteArray, at: Long = now) =
        (decode(frame, at) as? DecodeResult.Rejected)?.reason

    // ── Format ───────────────────────────────────────────────────────────────

    @Test
    fun `header is 64 bytes and aad 47 bytes`() {
        val e = envelope()
        assertEquals(64 + 40, EnvelopeCodec.encode(e).size)
        assertEquals(47, e.aad().size)
    }

    @Test
    fun `id matches an independent SHA-256 computation`() {
        // Vecteur calculé avec Python (hashlib) sur la même AAD et le même payload
        assertEquals("08d653c7b6598f30564871ad2fdff254", envelope().id.hex)
    }

    @Test
    fun `round trip keeps every field`() {
        val original = envelope(kind = EnvelopeKind.ACK, hopLimit = 3, hopCount = 7, ack = ByteArray(16) { 9 })
        val decoded = (decode(EnvelopeCodec.encode(original)) as DecodeResult.Accepted).envelope
        assertEquals(original, decoded)
        assertEquals(EnvelopeKind.ACK, decoded.kind)
        assertEquals(3, decoded.hopLimit)
        assertEquals(7, decoded.hopCount)
        assertArrayEquals(original.ack, decoded.ack)
        assertArrayEquals(original.payload, decoded.payload)
    }

    @Test
    fun `header layout is big-endian at the documented offsets`() {
        val frame = EnvelopeCodec.encode(envelope(hopLimit = 5, hopCount = 2))
        assertEquals(0x01.toByte(), frame[0])                 // version
        assertEquals(EnvelopeKind.DATA.code, frame[1])        // kind
        assertEquals(0xAA.toByte(), frame[18])                // destTag commence à 18
        assertEquals(java.nio.ByteBuffer.wrap(frame, 34, 8).long, now)          // createdAt
        assertEquals(java.nio.ByteBuffer.wrap(frame, 42, 4).int, 6 * 3600)      // lifetime
        assertEquals(5, frame[46].toInt())                    // hopLimit
        assertEquals(2, frame[47].toInt())                    // hopCount
    }

    // ── Identifiant et AAD ───────────────────────────────────────────────────

    @Test
    fun `id and aad ignore hopCount`() {
        val e = envelope()
        val relayed = e.forRelay().forRelay()
        assertEquals(2, relayed.hopCount)
        assertEquals(e.id, relayed.id)
        assertArrayEquals(e.aad(), relayed.aad())
    }

    @Test
    fun `id changes when any immutable field changes`() {
        val base = envelope().id
        listOf(
            envelope(kind = EnvelopeKind.ANNOUNCE),
            envelope(destTag = ByteArray(16) { 1 }),
            envelope(createdAt = now + 1),
            envelope(lifetime = 3600),
            envelope(hopLimit = 9),
            envelope(ack = ByteArray(16) { 1 }),
            envelope(payload = ByteArray(40) { 7 })
        ).forEach { assertNotEquals(base, it.id) }
    }

    @Test
    fun `hopCount saturates at 255`() {
        assertEquals(255, envelope(hopCount = 255).forRelay().hopCount)
    }

    @Test
    fun `forged id is rejected`() {
        val frame = EnvelopeCodec.encode(envelope())
        frame[2] = (frame[2].toInt() xor 1).toByte()
        assertEquals(RejectReason.ID_MISMATCH, rejected(frame))
    }

    @Test
    fun `tampered immutable header byte is rejected`() {
        val frame = EnvelopeCodec.encode(envelope())
        frame[45] = (frame[45].toInt() xor 1).toByte()   // dernier octet de lifetime
        assertEquals(RejectReason.ID_MISMATCH, rejected(frame))
    }

    @Test
    fun `tampered hopCount is still accepted`() {
        val frame = EnvelopeCodec.encode(envelope())
        frame[47] = 200.toByte()
        val decoded = (decode(frame) as DecodeResult.Accepted).envelope
        assertEquals(200, decoded.hopCount)
    }

    // ── Bornes de taille et champs ───────────────────────────────────────────

    @Test
    fun `size bounds`() {
        assertEquals(RejectReason.TOO_SHORT, rejected(ByteArray(0)))
        assertEquals(RejectReason.TOO_SHORT, rejected(EnvelopeCodec.encode(envelope()).copyOf(103)))

        val maxPayload = Envelope.MAX_SIZE - Envelope.HEADER_SIZE
        val max = EnvelopeCodec.encode(envelope(payload = ByteArray(maxPayload)))
        assertTrue(decode(max) is DecodeResult.Accepted)
        assertEquals(RejectReason.TOO_LARGE, rejected(max + 0))
        assertThrows(IllegalArgumentException::class.java) { envelope(payload = ByteArray(maxPayload + 1)) }
    }

    @Test
    fun `unknown version and kind are rejected`() {
        val badVersion = EnvelopeCodec.encode(envelope()).also { it[0] = 0x02 }
        assertEquals(RejectReason.UNKNOWN_VERSION, rejected(badVersion))
        val badKind = EnvelopeCodec.encode(envelope()).also { it[1] = 0x7F }
        assertEquals(RejectReason.UNKNOWN_KIND, rejected(badKind))
    }

    @Test
    fun `lifetime must be in 1 second to 24 hours`() {
        assertTrue(decode(EnvelopeCodec.encode(envelope(lifetime = 24 * 3600))) is DecodeResult.Accepted)
        listOf(0, -1, 24 * 3600 + 1).forEach { bad ->
            val frame = EnvelopeCodec.encode(envelope()).also {
                java.nio.ByteBuffer.wrap(it).putInt(42, bad)
            }
            assertEquals("lifetime=$bad", RejectReason.BAD_LIFETIME, rejected(frame))
        }
    }

    @Test
    fun `hop fields are never a reason to reject`() {
        val frame = EnvelopeCodec.encode(envelope(hopLimit = 0, hopCount = 255))
        assertTrue(decode(frame) is DecodeResult.Accepted)
    }

    // ── Horloge : ±2 h ───────────────────────────────────────────────────────

    @Test
    fun `creation up to 2 hours in the future is accepted, not beyond`() {
        val skew = Envelope.CLOCK_SKEW_SECONDS
        assertTrue(decode(EnvelopeCodec.encode(envelope(createdAt = now + skew))) is DecodeResult.Accepted)
        assertEquals(RejectReason.FROM_THE_FUTURE, rejected(EnvelopeCodec.encode(envelope(createdAt = now + skew + 1))))
    }

    @Test
    fun `expiry keeps a 2 hour margin after the lifetime`() {
        val e = envelope(createdAt = now, lifetime = 3600)
        val lastValid = now + 3600 + Envelope.CLOCK_SKEW_SECONDS
        assertTrue(decode(EnvelopeCodec.encode(e), at = lastValid) is DecodeResult.Accepted)
        assertEquals(RejectReason.EXPIRED, rejected(EnvelopeCodec.encode(e), at = lastValid + 1))
        assertEquals(lastValid, e.expiresAt)
    }

    @Test
    fun `negative timestamp is rejected`() {
        val frame = EnvelopeCodec.encode(envelope()).also { java.nio.ByteBuffer.wrap(it).putLong(34, -5) }
        assertEquals(RejectReason.BAD_TIMESTAMP, rejected(frame))
    }

    // ── Robustesse ───────────────────────────────────────────────────────────

    @Test
    fun `random frames never throw`() {
        val random = java.util.Random(7)
        repeat(5_000) {
            val frame = ByteArray(random.nextInt(Envelope.MAX_SIZE + 64)).also(random::nextBytes)
            decode(frame)   // ne doit jamais lever d'exception
        }
    }
}
