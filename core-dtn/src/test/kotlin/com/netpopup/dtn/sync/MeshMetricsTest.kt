package com.netpopup.dtn.sync

import com.netpopup.dtn.envelope.Envelope
import com.netpopup.dtn.envelope.EnvelopeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mesure le routage épidémique sur un réseau simulé : couverture, profondeur des chemins
 * et coût radio. Ce ne sont pas des garanties : ils fixent l'ordre de grandeur que le test
 * terrain (mesh-23, #24) viendra confronter à la réalité, et qui décidera d'un plafond de
 * sauts ou de Spray-and-Wait.
 */
class MeshMetricsTest {

    private fun run(nodesCount: Int, meetings: Int, messages: Int, seed: Int): Metrics {
        val sim = MeshSimulator(seed = seed)
        val nodes = (1..nodesCount).map { sim.node("N$it") }
        val created = (0 until messages).map { i ->
            val e = Envelope(EnvelopeKind.DATA, ByteArray(16) { i.toByte() }, sim.nowSeconds, 6 * 3600,
                payload = ByteArray(40).also { it[0] = i.toByte() })
            sim.create(nodes[i % nodesCount], e)
            e
        }
        var sentTotal = 0
        repeat(meetings) {
            val a = nodes[sim.random.nextInt(nodes.size)]
            var b = a
            while (b === a) b = nodes[sim.random.nextInt(nodes.size)]
            val r = sim.meet(a, b, contactSeconds = 20)
            sentTotal += r.sentByA + r.sentByB
            sim.advance(60)
        }
        val coverage = created.sumOf { sim.holders(it.id).size }.toDouble() / (created.size * nodesCount)
        val maxHops = nodes.flatMap { it.received }.maxOfOrNull { it.hopCount } ?: 0
        return Metrics(coverage, maxHops, sentTotal.toDouble() / created.size)
    }

    private data class Metrics(val coverage: Double, val maxHops: Int, val transmissionsPerMessage: Double)

    @Test
    fun `20 devices, 10 messages, 150 random meetings`() {
        val m = run(nodesCount = 20, meetings = 150, messages = 10, seed = 7)
        println("MESH-METRICS 20 nodes/150 meetings: coverage=${"%.2f".format(m.coverage)} maxHops=${m.maxHops} transmissions/message=${"%.1f".format(m.transmissionsPerMessage)}")
        assertTrue("coverage=${m.coverage}", m.coverage > 0.9)
        // Une transmission par appareil qui obtient le message est le minimum : n - 1 = 19.
        assertTrue("tx/msg=${m.transmissionsPerMessage}", m.transmissionsPerMessage >= 19.0 * m.coverage - 1)
    }

    @Test
    fun `sparse network leaves messages undelivered, which is why TTL is the only bound`() {
        val m = run(nodesCount = 20, meetings = 10, messages = 10, seed = 7)
        println("MESH-METRICS 20 nodes/10 meetings: coverage=${"%.2f".format(m.coverage)} maxHops=${m.maxHops}")
        assertTrue(m.coverage < 0.5)
        assertEquals(true, m.maxHops >= 1)
    }
}
