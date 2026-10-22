package com.netpopup.dtn.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class BloomAndBucketTest {

    private fun id(n: Int) = ByteArray(16).also { it[0] = n.toByte(); it[1] = (n shr 8).toByte(); it[2] = (n shr 16).toByte() }
    private val salt = ByteArray(16) { it.toByte() }

    @Test
    fun `bloom filter has no false negatives`() {
        val filter = BloomFilter.create(5_000, 0.01, salt)
        repeat(5_000) { filter.add(id(it)) }
        assertTrue((0 until 5_000).all { filter.mightContain(id(it)) })
    }

    @Test
    fun `5000 ids at 1 percent take about 6 KiB and stay under 2 percent false positives`() {
        val filter = BloomFilter.create(5_000, 0.01, salt)
        repeat(5_000) { filter.add(id(it)) }
        assertTrue("size=${filter.bits.size}", filter.bits.size in 5_500..6_500)
        val falsePositives = (100_000 until 110_000).count { filter.mightContain(id(it)) }
        assertTrue("fp=$falsePositives/10000", falsePositives < 200)
    }

    @Test
    fun `a different salt moves the false positives`() {
        val a = BloomFilter.create(1_000, 0.05, salt)
        val b = BloomFilter.create(1_000, 0.05, ByteArray(16) { 99 })
        repeat(1_000) { a.add(id(it)); b.add(id(it)) }
        val fpA = (50_000 until 60_000).filter { a.mightContain(id(it)) }.toSet()
        val fpB = (50_000 until 60_000).filter { b.mightContain(id(it)) }.toSet()
        assertTrue(fpA.isNotEmpty() && fpB.isNotEmpty())
        assertNotEquals(fpA, fpB)
    }

    @Test
    fun `inconsistent wire parameters are refused`() {
        assertNull(BloomFilter.fromWire(64, 0, salt, ByteArray(8)))        // 0 hachage
        assertNull(BloomFilter.fromWire(64, 99, salt, ByteArray(8)))       // trop de hachages
        assertNull(BloomFilter.fromWire(64, 3, ByteArray(5), ByteArray(8))) // sel trop court
        assertNull(BloomFilter.fromWire(64, 3, salt, ByteArray(3)))        // mauvais nombre d'octets
    }

    @Test
    fun `token bucket allows a burst of 20 then one per second`() {
        val bucket = TokenBucket(20, 1.0)
        assertEquals(20, (1..30).count { bucket.tryTake(0) })
        assertFalse(bucket.tryTake(999))
        assertTrue(bucket.tryTake(1_000))
        assertFalse(bucket.tryTake(1_000))
        // 5 s plus tard : 5 jetons
        assertEquals(5, (1..10).count { bucket.tryTake(6_000) })
    }

    @Test
    fun `token bucket never holds more than its capacity`() {
        val bucket = TokenBucket(3, 10.0)
        bucket.tryTake(0)
        assertEquals(3, (1..10).count { bucket.tryTake(3_600_000) })
    }

    @Test
    fun `token bucket ignores a clock that goes backwards`() {
        val bucket = TokenBucket(1, 1.0)
        assertTrue(bucket.tryTake(10_000))
        assertFalse(bucket.tryTake(5_000))
        assertFalse(bucket.tryTake(10_500))
        assertTrue(bucket.tryTake(11_000))
    }

    @Test
    fun `frames survive random garbage`() {
        val random = Random(3)
        repeat(5_000) {
            val frame = ByteArray(random.nextInt(FrameCodec.MAX_FRAME_SIZE + 10)).also(random::nextBytes)
            FrameCodec.decode(frame)   // ne doit jamais lever
        }
    }

    @Test
    fun `inventory round trip, and hostile sizes are refused`() {
        val filter = BloomFilter.create(100, 0.01, salt).also { f -> repeat(100) { f.add(id(it)) } }
        val decoded = FrameCodec.decode(FrameCodec.encodeInventory(filter)) as Frame.Inventory
        assertTrue((0 until 100).all { decoded.filter.mightContain(id(it)) })
        assertEquals(filter.bitCount, decoded.filter.bitCount)

        val frame = FrameCodec.encodeInventory(filter)
        java.nio.ByteBuffer.wrap(frame).putInt(1, Int.MAX_VALUE)   // bitCount hostile
        assertNull(FrameCodec.decode(frame))
        assertNull(FrameCodec.decode(frame.copyOf(frame.size - 1)))
        assertNull(FrameCodec.decode(ByteArray(0)))
        assertNull(FrameCodec.decode(byteArrayOf(0x04)))               // enveloppe vide
        assertNull(FrameCodec.decode(byteArrayOf(0x7F, 1, 2, 3)))      // type inconnu
        assertNull(FrameCodec.decode(ByteArray(FrameCodec.MAX_FRAME_SIZE + 1)))
    }
}
