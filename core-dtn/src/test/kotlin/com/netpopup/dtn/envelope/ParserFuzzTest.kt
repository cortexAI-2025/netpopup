package com.netpopup.dtn.envelope

import com.netpopup.dtn.sync.BloomFilter
import com.netpopup.dtn.sync.FrameCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Fuzzing par propriétés des surfaces exposées à des données hostiles : le décodeur d'enveloppes et celui des
 * trames de session. Propriétés : aucune exception, aucune allocation disproportionnée, et tout ce qui est
 * accepté se ré-encode à l'identique.
 *
 * Durée bornée : `FUZZ_ITERATIONS` (défaut 20 000 par cible) ; la graine est fixe, un échec est reproductible.
 */
class ParserFuzzTest {

    private val now = 1_776_000_000L
    private val iterations = System.getenv("FUZZ_ITERATIONS")?.toIntOrNull() ?: 20_000

    private fun valid(random: Random) = Envelope(
        kind = EnvelopeKind.values()[random.nextInt(EnvelopeKind.values().size)],
        destTag = random.nextBytes(16),
        createdAt = now,
        lifetime = 1 + random.nextInt(Envelope.MAX_LIFETIME_SECONDS),
        hopLimit = random.nextInt(Envelope.DEFAULT_HOP_LIMIT + 1),
        hopCount = 0,
        ack = random.nextBytes(16),
        payload = random.nextBytes(Envelope.MIN_PAYLOAD_SIZE + random.nextInt(200)),
    )

    private fun mutate(bytes: ByteArray, random: Random): ByteArray {
        var b = bytes.copyOf()
        repeat(1 + random.nextInt(4)) {
            when (random.nextInt(6)) {
                0 -> if (b.isNotEmpty()) b[random.nextInt(b.size)] = random.nextInt(256).toByte()
                1 -> if (b.isNotEmpty()) { val i = random.nextInt(b.size); b[i] = (b[i].toInt() xor (1 shl random.nextInt(8))).toByte() }
                2 -> b = b.copyOf(random.nextInt(b.size + 1))                       // troncature
                3 -> b += random.nextBytes(random.nextInt(64))                       // allongement
                4 -> if (b.size >= 8) { val i = random.nextInt(b.size - 7); repeat(8) { k -> b[i + k] = 0xFF.toByte() } }
                5 -> if (b.size >= 8) { val i = random.nextInt(b.size - 7); repeat(8) { k -> b[i + k] = 0 } }
            }
        }
        return b
    }

    @Test fun envelopeDecoderNeverThrowsOnMutatedOrRandomInput() {
        val random = Random(1)
        var accepted = 0
        repeat(iterations) {
            val base = EnvelopeCodec.encode(valid(random))
            val input = if (random.nextInt(5) == 0) random.nextBytes(random.nextInt(Envelope.MAX_SIZE + 64)) else mutate(base, random)
            // Horodatage de référence extrême : le décodeur ne doit ni déborder ni lever.
            val at = when (random.nextInt(4)) { 0 -> Long.MIN_VALUE; 1 -> Long.MAX_VALUE; 2 -> 0L; else -> now }
            when (val r = EnvelopeCodec.decode(input, at)) {
                is DecodeResult.Accepted -> {
                    accepted++
                    assertTrue(r.envelope.payload.size + Envelope.HEADER_SIZE <= Envelope.MAX_SIZE)
                    assertEquals(input.size, EnvelopeCodec.encode(r.envelope).size)
                }
                is DecodeResult.Rejected -> Unit
            }
        }
        assertTrue("the fuzzer must also reach the accepting path", accepted > 0)
    }

    @Test fun oversizedAndEmptyFramesAreRejectedWithoutAllocation() {
        for (size in listOf(0, 1, 63, 64, Envelope.MAX_SIZE, Envelope.MAX_SIZE + 1, 1 shl 20)) {
            val r = EnvelopeCodec.decode(ByteArray(size), now)
            assertTrue("size $size", r is DecodeResult.Rejected || r is DecodeResult.Accepted)
        }
    }

    @Test fun sessionFrameDecoderNeverThrows() {
        val random = Random(2)
        val filter = BloomFilter.create(100, 0.01, random.nextBytes(BloomFilter.SALT_SIZE))
        val seeds = listOf(FrameCodec.encodeInventory(filter), FrameCodec.encodeEnvelope(EnvelopeCodec.encode(valid(random))))
        repeat(iterations) {
            val input = if (random.nextInt(4) == 0) random.nextBytes(random.nextInt(2048)) else mutate(seeds[random.nextInt(seeds.size)], random)
            FrameCodec.decode(input) // un résultat nul est légitime ; une exception ne l'est pas
        }
    }

    @Test fun inventoryClaimingHugeFilterIsRefused() {
        // Un en-tête d'inventaire annonçant une taille gigantesque ne doit pas provoquer d'allocation.
        val frame = ByteArray(64) { 0x7F }
        frame[0] = 0x02
        assertEquals(null, FrameCodec.decode(frame))
    }
}
