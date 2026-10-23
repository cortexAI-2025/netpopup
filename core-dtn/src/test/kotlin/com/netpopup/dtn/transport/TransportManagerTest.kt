package com.netpopup.dtn.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TransportManagerTest {

    private class Harness(
        scope: TestScope,
        val transports: List<FakeTransport>,
        config: TransportManagerConfig,
        seed: Int
    ) {
        val events = mutableListOf<LinkEvent>()
        val manager = TransportManager(
            transports, scope.backgroundScope, config, Random(seed), clockMillis = { scope.currentTime }
        )

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { manager.events.toList(events) }
        }

        fun downs() = events.filterIsInstance<LinkEvent.LinkDown>()
        fun ups() = events.filterIsInstance<LinkEvent.LinkUp>()
    }

    private fun TestScope.harness(
        vararg transports: FakeTransport,
        config: TransportManagerConfig = TransportManagerConfig(),
        seed: Int = 1
    ) = Harness(this, transports.toList().ifEmpty { listOf(FakeTransport()) }, config, seed)

    // ── Démarrage ────────────────────────────────────────────────────────────

    @Test
    fun `defaults match the architecture document`() {
        val config = TransportManagerConfig()
        assertEquals(4, config.maxConnections)
        assertEquals(5.minutes, config.rotationMin)
        assertEquals(10.minutes, config.rotationMax)
    }

    @Test
    fun `a transport that fails to start does not block the others`() = runTest {
        val broken = FakeTransport(TransportId.LORA, startFails = true)
        val nearby = FakeTransport(TransportId.NEARBY)
        val h = harness(broken, nearby)

        assertEquals(listOf(TransportId.LORA), h.manager.start())
        assertEquals(TransportState.RUNNING, nearby.state.value)

        val peer = nearby.peer("p1")
        nearby.simulatePeerConnected(peer)
        runCurrent()
        assertEquals(listOf(peer), h.ups().map { it.peer })
    }

    @Test
    fun `two transports cannot share an id`() = runTest {
        var failed = false
        try {
            TransportManager(listOf(FakeTransport(), FakeTransport()), backgroundScope)
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    // ── Liens et trames ──────────────────────────────────────────────────────

    @Test
    fun `frames reach the core only from linked peers`() = runTest {
        val t = FakeTransport()
        val h = harness(t)
        h.manager.start()

        val known = t.peer("known"); val stranger = t.peer("stranger")
        t.simulatePeerConnected(known)
        t.simulateFrame(known, byteArrayOf(1, 2, 3))
        t.simulateFrame(stranger, byteArrayOf(9))
        runCurrent()

        val frames = h.events.filterIsInstance<LinkEvent.FrameReceived>()
        assertEquals(1, frames.size)
        assertEquals(known, frames.single().peer)
        assertArrayEquals(byteArrayOf(1, 2, 3), frames.single().frame)
    }

    @Test
    fun `a lost peer closes its link and later frames are ignored`() = runTest {
        val t = FakeTransport()
        val h = harness(t)
        h.manager.start()
        val p = t.peer("p")
        t.simulatePeerConnected(p); runCurrent()
        t.simulatePeerLost(p); runCurrent()
        t.simulateFrame(p, byteArrayOf(1)); runCurrent()

        assertEquals(LinkDownReason.LOST, h.downs().single().reason)
        assertTrue(h.events.filterIsInstance<LinkEvent.FrameReceived>().isEmpty())
        assertTrue(h.manager.links().isEmpty())
    }

    // ── Plafond global ───────────────────────────────────────────────────────

    @Test
    fun `a fifth peer is disconnected when the cap is four`() = runTest {
        val t = FakeTransport()
        val h = harness(t)
        h.manager.start()
        val peers = (1..6).map { t.peer("p$it") }
        peers.forEach { t.simulatePeerConnected(it) }
        runCurrent()

        assertEquals(peers.take(4), h.ups().map { it.peer })
        assertEquals(peers.drop(4), t.disconnected)
        assertEquals(2, h.manager.rejectedConnections)
        assertEquals(4, h.manager.links().size)
    }

    @Test
    fun `the cap is global, across transports`() = runTest {
        val nearby = FakeTransport(TransportId.NEARBY)
        val internet = FakeTransport(TransportId.INTERNET)
        val h = harness(nearby, internet, config = TransportManagerConfig(maxConnections = 3))
        h.manager.start()

        nearby.simulatePeerConnected(nearby.peer("a")); nearby.simulatePeerConnected(nearby.peer("b"))
        internet.simulatePeerConnected(internet.peer("c")); internet.simulatePeerConnected(internet.peer("d"))
        runCurrent()

        assertEquals(3, h.manager.links().size)
        assertEquals(listOf(internet.peer("d")), internet.disconnected)
    }

    @Test
    fun `a duplicate connection event does not open a second link`() = runTest {
        val t = FakeTransport()
        val h = harness(t)
        h.manager.start()
        val p = t.peer("p")
        t.simulatePeerConnected(p); t.simulatePeerConnected(p); runCurrent()
        assertEquals(1, h.ups().size)
        assertEquals(1, h.manager.rejectedConnections)
    }

    // ── Rotation ─────────────────────────────────────────────────────────────

    @Test
    fun `each link is closed between 5 and 10 minutes, at its own time`() = runTest {
        val t = FakeTransport()
        val h = harness(t, seed = 5)
        h.manager.start()
        val peers = (1..4).map { t.peer("p$it") }
        peers.forEach { t.simulatePeerConnected(it) }
        runCurrent()

        advanceTimeBy(5.minutes.inWholeMilliseconds - 1); runCurrent()
        assertTrue("rien avant 5 min", h.downs().isEmpty())

        val closedAt = mutableMapOf<PeerHandle, Long>()
        while (closedAt.size < 4 && currentTime <= 10.minutes.inWholeMilliseconds + 1) {
            advanceTimeBy(1_000); runCurrent()
            h.downs().forEach { closedAt.putIfAbsent(it.peer, currentTime) }
        }
        assertEquals(4, closedAt.size)
        assertTrue(closedAt.values.all { it in 5.minutes.inWholeMilliseconds..10.minutes.inWholeMilliseconds + 1_000 })
        assertTrue("instants distincts", closedAt.values.toSet().size > 1)
        assertTrue(h.downs().all { it.reason == LinkDownReason.ROTATED })
        assertEquals(peers.toSet(), t.disconnected.toSet())
    }

    @Test
    fun `rotation frees a place for a new peer`() = runTest {
        val t = FakeTransport()
        val h = harness(t, config = TransportManagerConfig(maxConnections = 1), seed = 2)
        h.manager.start()
        val first = t.peer("first"); val second = t.peer("second")
        t.simulatePeerConnected(first); runCurrent()
        t.simulatePeerConnected(second); runCurrent()
        assertEquals(listOf(second), t.disconnected)        // plein

        advanceTimeBy(10.minutes.inWholeMilliseconds + 1); runCurrent()
        t.simulatePeerConnected(second); runCurrent()       // la place s'est libérée
        assertEquals(listOf(first, second), h.ups().map { it.peer })
    }

    @Test
    fun `a rotated handle waits out the cooldown before coming back`() = runTest {
        val t = FakeTransport()
        val h = harness(t, config = TransportManagerConfig(maxConnections = 1), seed = 3)
        h.manager.start()
        val p = t.peer("p")
        t.simulatePeerConnected(p); runCurrent()

        // On s'arrête à la seconde où la rotation a lieu (entre 5 et 10 min)
        while (h.downs().isEmpty()) { advanceTimeBy(1_000); runCurrent() }
        assertEquals(LinkDownReason.ROTATED, h.downs().single().reason)

        t.simulatePeerConnected(p); runCurrent()            // tout de suite : refusé
        assertEquals(1, h.ups().size)

        advanceTimeBy(30.seconds.inWholeMilliseconds); runCurrent()
        t.simulatePeerConnected(p); runCurrent()            // 30 s après : toujours refusé
        assertEquals(1, h.ups().size)

        advanceTimeBy(31.seconds.inWholeMilliseconds); runCurrent()
        t.simulatePeerConnected(p); runCurrent()            // plus de 60 s après : accepté
        assertEquals(2, h.ups().size)
    }

    @Test
    fun `a lost peer cancels its rotation`() = runTest {
        val t = FakeTransport()
        val h = harness(t)
        h.manager.start()
        val p = t.peer("p")
        t.simulatePeerConnected(p); runCurrent()
        t.simulatePeerLost(p); runCurrent()

        advanceTimeBy(30.minutes.inWholeMilliseconds); runCurrent()
        assertTrue("pas de déconnexion tardive", t.disconnected.isEmpty())
        assertEquals(1, h.downs().size)
    }

    // ── Envoi ────────────────────────────────────────────────────────────────

    @Test
    fun `send reaches the transport, refuses oversize frames and unknown peers`() = runTest {
        val t = FakeTransport(capabilities = TransportCapabilities(100, 4, false, CostClass.FREE))
        val h = harness(t)
        h.manager.start()
        val p = t.peer("p")
        t.simulatePeerConnected(p); runCurrent()

        assertEquals(SendResult.SENT, h.manager.send(p, ByteArray(100)))
        assertEquals(SendResult.FRAME_TOO_LARGE, h.manager.send(p, ByteArray(101)))
        assertEquals(SendResult.PEER_GONE, h.manager.send(t.peer("nobody"), ByteArray(1)))
        assertEquals(1, t.sent.size)
    }

    @Test
    fun `a send that finds the peer gone closes the link`() = runTest {
        val t = FakeTransport()
        val h = harness(t)
        h.manager.start()
        val p = t.peer("p")
        t.simulatePeerConnected(p); runCurrent()
        t.simulatePeerLost(p)     // le transport oublie le pair avant que le gestionnaire ne traite l'événement
        t.simulatePeerConnected(p)
        runCurrent()
        t.sendResult = SendResult.PEER_GONE

        assertEquals(SendResult.PEER_GONE, h.manager.send(p, ByteArray(1)))
        assertTrue(h.manager.links().isEmpty())
    }

    @Test
    fun `links are ordered by cost, then by observed latency`() = runTest {
        val scarce = FakeTransport(TransportId.LORA, TransportCapabilities(200, 1, true, CostClass.SCARCE))
        val slow = FakeTransport(TransportId.INTERNET, TransportCapabilities(4096, 4, false, CostClass.FREE), sendDelayMillis = 400)
        val fast = FakeTransport(TransportId.NEARBY, TransportCapabilities(32_768, 4, false, CostClass.FREE), sendDelayMillis = 20)
        val h = harness(scarce, slow, fast)
        h.manager.start()
        val peers = listOf(scarce, slow, fast).map { it.peer(it.id.name) }
        scarce.simulatePeerConnected(peers[0]); slow.simulatePeerConnected(peers[1]); fast.simulatePeerConnected(peers[2])
        runCurrent()

        // Sans mesure : à coût égal, le plus ancien d'abord ; le rare passe en dernier
        assertEquals(CostClass.SCARCE, h.manager.links().last().costClass)

        h.manager.broadcast(ByteArray(10))
        val order = h.manager.links().map { it.peer.transport }
        assertEquals(listOf(TransportId.NEARBY, TransportId.INTERNET, TransportId.LORA), order)
    }

    // ── Arrêt et indisponibilité ─────────────────────────────────────────────

    @Test
    fun `a transport becoming unavailable drops only its own links`() = runTest {
        val nearby = FakeTransport(TransportId.NEARBY)
        val internet = FakeTransport(TransportId.INTERNET)
        val h = harness(nearby, internet)
        h.manager.start()
        nearby.simulatePeerConnected(nearby.peer("n1")); internet.simulatePeerConnected(internet.peer("i1"))
        runCurrent()

        internet.simulateState(TransportState.UNAVAILABLE); runCurrent()
        assertEquals(listOf(internet.peer("i1")), h.downs().map { it.peer })
        assertEquals(LinkDownReason.TRANSPORT_STOPPED, h.downs().single().reason)
        assertEquals(listOf(nearby.peer("n1")), h.manager.links().map { it.peer })
    }

    @Test
    fun `stop closes every link and stops every transport`() = runTest {
        val t = FakeTransport()
        val h = harness(t)
        h.manager.start()
        t.simulatePeerConnected(t.peer("a")); t.simulatePeerConnected(t.peer("b")); runCurrent()

        h.manager.stop(); runCurrent()
        assertEquals(1, t.stopCount)
        assertEquals(2, h.downs().count { it.reason == LinkDownReason.TRANSPORT_STOPPED })
        assertTrue(h.manager.links().isEmpty())

        advanceTimeBy(30.minutes.inWholeMilliseconds); runCurrent()
        assertTrue("les minuteurs de rotation sont annulés", t.disconnected.isEmpty())
    }
}
