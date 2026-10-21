package com.netpopup.dtn.envelope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DestTagsTest {

    private val key = ByteArray(32) { it.toByte() }
    private val otherKey = ByteArray(32) { (it + 1).toByte() }
    private val hour = DestTags.EPOCH_SECONDS

    @Test
    fun `tag matches an independent HMAC-SHA256 computation`() {
        // Vecteurs calculés avec Python (hmac + hashlib), époque en 8 octets big-endian signés
        assertEquals("ee837890070fd1f65b529f85f92edcbf", DestTags.tag(key, 493_000).toHex())
        assertEquals("9f0cd9b94097fe4929918d2b8942b344", DestTags.tag(key, 0).toHex())
        assertEquals("51e1793eae66e2c33112f804f9b834eb", DestTags.tag(key, -1).toHex())
    }

    @Test
    fun `epoch is the whole hour, floored`() {
        assertEquals(0, DestTags.epochOf(0))
        assertEquals(0, DestTags.epochOf(3599))
        assertEquals(1, DestTags.epochOf(3600))
        assertEquals(-1, DestTags.epochOf(-1))
    }

    @Test
    fun `tag changes every hour and with the key`() {
        val t0 = 1_776_000_000L
        assertNotEquals(DestTags.forSending(key, t0).toHex(), DestTags.forSending(key, t0 + hour).toHex())
        assertNotEquals(DestTags.forSending(key, t0).toHex(), DestTags.forSending(otherKey, t0).toHex())
    }

    private fun envelopeTo(key: ByteArray, createdAt: Long, tagEpoch: Long = DestTags.epochOf(createdAt)) =
        Envelope(
            kind = EnvelopeKind.DATA,
            destTag = DestTags.tag(key, tagEpoch),
            createdAt = createdAt,
            lifetime = Envelope.MAX_LIFETIME_SECONDS,
            payload = ByteArray(Envelope.MIN_PAYLOAD_SIZE)
        )

    @Test
    fun `an envelope delayed for hours still matches while it is valid`() {
        val receiverNow = 1_776_000_000L
        // Créée il y a 3 h, 10 h puis 25 h (durée de vie 24 h, marge 2 h) : toujours valide
        listOf(3L, 10L, 25L).forEach { delay ->
            val e = envelopeTo(key, receiverNow - delay * hour)
            assertTrue("delay=$delay h", EnvelopeCodec.decode(EnvelopeCodec.encode(e), receiverNow) is DecodeResult.Accepted)
            assertTrue("delay=$delay h", DestTags.matches(key, e))
        }
    }

    @Test
    fun `sender clock ahead by up to 2 hours still matches`() {
        val e = envelopeTo(key, 1_776_000_000L + 2 * hour)
        assertTrue(EnvelopeCodec.decode(EnvelopeCodec.encode(e), 1_776_000_000L) is DecodeResult.Accepted)
        assertTrue(DestTags.matches(key, e))
    }

    @Test
    fun `tag computed for another hour than createdAt does not match`() {
        val createdAt = 1_776_000_000L
        val e = envelopeTo(key, createdAt, tagEpoch = DestTags.epochOf(createdAt) + 1)
        assertFalse(DestTags.matches(key, e))
    }

    @Test
    fun `wrong key never matches`() {
        assertFalse(DestTags.matches(otherKey, envelopeTo(key, 1_776_000_000L)))
    }

    @Test
    fun `short keys are refused`() {
        assertThrows(IllegalArgumentException::class.java) { DestTags.tag(ByteArray(15), 0) }
    }
}
