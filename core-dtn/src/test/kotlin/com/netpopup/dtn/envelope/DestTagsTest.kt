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

    @Test
    fun `receiver accepts a sender clock off by up to 2 hours, not 3`() {
        val receiverNow = 1_776_000_000L
        listOf(-2L, -1L, 0L, 1L, 2L).forEach { offset ->
            val tag = DestTags.forSending(key, receiverNow + offset * hour)
            assertTrue("offset=$offset h", DestTags.matches(key, tag, receiverNow))
        }
        listOf(-3L, 3L).forEach { offset ->
            val tag = DestTags.forSending(key, receiverNow + offset * hour)
            assertFalse("offset=$offset h", DestTags.matches(key, tag, receiverNow))
        }
    }

    @Test
    fun `window holds five distinct tags`() {
        val tags = DestTags.acceptedTags(key, 1_776_000_000L)
        assertEquals(5, tags.size)
        assertEquals(5, tags.map { it.toHex() }.toSet().size)
    }

    @Test
    fun `wrong key never matches`() {
        val tag = DestTags.forSending(key, 1_776_000_000L)
        assertFalse(DestTags.matches(otherKey, tag, 1_776_000_000L))
    }

    @Test
    fun `short keys are refused`() {
        assertThrows(IllegalArgumentException::class.java) { DestTags.tag(ByteArray(15), 0) }
    }
}
