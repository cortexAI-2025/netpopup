package com.netpopup.transport.nearby

import com.netpopup.dtn.transport.PeerHandle
import com.netpopup.dtn.transport.SendResult
import com.netpopup.dtn.transport.TransportEvent
import com.netpopup.dtn.transport.TransportId
import com.netpopup.dtn.transport.TransportState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyTransportTest {

    /** Nom plus grand que tout nom local possible : le transport doit demander la connexion. */
    private val higher = "ffffffffff"

    /** Nom plus petit que tout nom local possible : le transport attend. */
    private val lower = "00000000"

    private class Rig(scope: TestScope, config: NearbyConfig, seed: Int) {
        val api = FakeNearbyApi()
        val transport = NearbyTransport(api, scope.backgroundScope, config, Random(seed))
        val events = mutableListOf<TransportEvent>()

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { transport.events.toList(events) }
        }

        fun connected() = events.filterIsInstance<TransportEvent.PeerConnected>().map { it.peer.value }
        fun lost() = events.filterIsInstance<TransportEvent.PeerLost>().map { it.peer.value }
        fun frames() = events.filterIsInstance<TransportEvent.FrameReceived>()
    }

    private fun TestScope.rig(config: NearbyConfig = NearbyConfig(), seed: Int = 1) = Rig(this, config, seed)

    private fun peer(id: String) = PeerHandle(TransportId.NEARBY, id)

    // ── Contrat ──────────────────────────────────────────────────────────────

    @Test
    fun `capabilities follow the architecture document`() = runTest {
        val caps = rig().transport.capabilities
        assertEquals(32_768, caps.maxFrameBytes)
        assertEquals(4, caps.maxConcurrentPeers)
        assertFalse(caps.isBroadcastOnly)
        assertEquals(com.netpopup.dtn.transport.CostClass.FREE, caps.costClass)
        assertEquals(TransportId.NEARBY, rig().transport.id)
    }

    // ── Cycles de diffusion ──────────────────────────────────────────────────

    @Test
    fun `it advertises under a random 8 hex name and discovers at the same time`() = runTest {
        val r = rig()
        r.transport.start(); runCurrent()

        val name = r.api.advertisedNames().single()
        assertTrue(name, Regex("[0-9a-f]{8}").matches(name))
        assertEquals(1, r.api.count("discover"))
        assertEquals(TransportState.RUNNING, r.transport.state.value)
    }

    @Test
    fun `it follows a 20 s on, 40 s off cycle and changes its name each time`() = runTest {
        val r = rig()
        r.transport.start(); runCurrent()
        assertEquals(1, r.api.count("advertise:"))

        advanceTimeBy(19_999); runCurrent()
        assertEquals(0, r.api.count("stopAdvertising"))
        advanceTimeBy(1); runCurrent()                       // t = 20 s
        assertEquals(1, r.api.count("stopAdvertising"))
        assertEquals(1, r.api.count("stopDiscovery"))

        advanceTimeBy(39_999); runCurrent()                  // t = 59,999 s : encore en pause
        assertEquals(1, r.api.count("advertise:"))
        advanceTimeBy(1); runCurrent()                       // t = 60 s : le cycle reprend
        assertEquals(2, r.api.count("advertise:"))
        assertEquals(2, r.transport.cycles)

        val names = r.api.advertisedNames()
        assertNotEquals(names[0], names[1])
    }

    @Test
    fun `connections survive the pauses`() = runTest {
        val r = rig()
        r.transport.start(); runCurrent()
        r.api.connect("e1"); runCurrent()

        advanceTimeBy(25_000); runCurrent()                  // en pause
        assertEquals(0, r.api.count("disconnect"))
        assertEquals(0, r.api.count("stopAll"))
        assertEquals(SendResult.SENT, r.transport.send(peer("e1"), ByteArray(3)))
    }

    @Test
    fun `continuous discovery stays available when a second phone starts 30 seconds later`() = runTest {
        val first = rig(NearbyConfig(continuous = true))
        first.transport.start(); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(0, first.api.count("stopDiscovery"))
        assertEquals(0, first.api.count("stopAdvertising"))
        assertTrue(first.transport.diagnostics.value.discovering)
        assertTrue(first.transport.diagnostics.value.advertising)
        val second = rig(NearbyConfig(continuous = true), seed = 2)
        second.transport.start(); runCurrent()
        val a = first.api.advertisedNames().single()
        val b = second.api.advertisedNames().single()
        first.api.listener!!.onEndpointFound("second", b)
        second.api.listener!!.onEndpointFound("first", a)
        runCurrent()
        assertEquals(1, first.api.count("request:") + second.api.count("request:"))
        first.api.connect("second"); second.api.connect("first"); runCurrent()
        assertEquals(1, first.transport.diagnostics.value.connected)
        assertEquals(1, second.transport.diagnostics.value.connected)
        advanceTimeBy(180_000); runCurrent()
        assertEquals(1, first.api.count("discover"))
        assertEquals(1, second.api.count("discover"))
    }

    @Test
    fun `one sided discovery eventually initiates even from the larger name`() = runTest {
        val r = rig(NearbyConfig(continuous = true))
        r.transport.start(); runCurrent()
        r.api.listener!!.onEndpointFound("only-visible-here", lower); runCurrent()
        assertEquals(0, r.api.count("request:"))
        advanceTimeBy(3_000); runCurrent()
        assertEquals(1, r.api.count("request:"))
        r.api.connect("only-visible-here"); runCurrent()
        assertEquals(listOf("only-visible-here"), r.connected())
    }

    @Test
    fun `silent handshake times out and retries a still discovered endpoint`() = runTest {
        val r = rig(NearbyConfig(continuous = true, maxConnections = 1))
        r.transport.start(); runCurrent()
        r.api.listener!!.onEndpointFound("silent", higher); runCurrent()
        assertEquals(1, r.transport.diagnostics.value.connecting)
        advanceTimeBy(15_000); runCurrent()
        assertEquals(0, r.transport.diagnostics.value.connecting)
        assertTrue(r.api.calls.contains("disconnect:silent"))
        r.api.listener!!.onConnectionResult("silent", true); runCurrent()
        assertTrue("late success must not resurrect an expired handshake", r.connected().isEmpty())
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, r.api.count("request:"))
    }

    @Test
    fun `continuous mode exposes SDK errors and retries discovery`() = runTest {
        val r = rig(NearbyConfig(continuous = true))
        r.api.discoveryFailure = NearbyApiException(8038, IllegalStateException("sdk"))
        r.transport.start(); runCurrent()
        assertTrue(r.transport.diagnostics.value.advertising)
        assertFalse(r.transport.diagnostics.value.discovering)
        assertEquals("Discovery: Nearby status 8038", r.transport.diagnostics.value.lastFailure)
        r.api.discoveryFailure = null
        advanceTimeBy(20_000); runCurrent()
        assertTrue(r.transport.diagnostics.value.discovering)
        assertEquals(null, r.transport.diagnostics.value.lastFailure)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, r.api.count("discover"))
    }

    // ── Qui demande la connexion ─────────────────────────────────────────────

    @Test
    fun `only the device with the smaller name requests the connection`() = runTest {
        val r = rig()
        r.transport.start(); runCurrent()
        val local = r.api.advertisedNames().single()

        r.api.listener!!.onEndpointFound("big", higher)
        r.api.listener!!.onEndpointFound("small", lower)
        runCurrent()

        assertEquals(1, r.api.count("request:"))
        assertTrue(r.api.calls.contains("request:$local:big"))
        assertFalse(r.api.calls.any { it.endsWith(":small") })
    }

    @Test
    fun `an endpoint already being connected is not requested twice`() = runTest {
        val r = rig()
        r.transport.start(); runCurrent()
        r.api.listener!!.onEndpointFound("e", higher)
        r.api.listener!!.onEndpointFound("e", higher)
        runCurrent()
        assertEquals(1, r.api.count("request:"))

        r.api.connect("e"); runCurrent()
        r.api.listener!!.onEndpointFound("e", higher)       // déjà connecté
        runCurrent()
        assertEquals(1, r.api.count("request:"))
    }

    @Test
    fun `a failed request frees its place`() = runTest {
        val r = rig(NearbyConfig(maxConnections = 1))
        r.transport.start(); runCurrent()
        r.api.requestFailure = IllegalStateException("radio busy")
        r.api.listener!!.onEndpointFound("a", higher); runCurrent()

        r.api.requestFailure = null
        r.api.listener!!.onEndpointFound("b", higher); runCurrent()
        assertTrue(r.api.calls.any { it.endsWith(":b") })
    }

    // ── Plafond ──────────────────────────────────────────────────────────────

    @Test
    fun `pending handshakes count against the cap, extra incoming links are rejected`() = runTest {
        val r = rig(NearbyConfig(maxConnections = 2))
        r.transport.start(); runCurrent()

        r.api.listener!!.onEndpointFound("e1", higher)
        r.api.listener!!.onEndpointFound("e2", higher)
        r.api.listener!!.onEndpointFound("e3", higher)      // plein : pas de demande
        r.api.listener!!.onConnectionInitiated("e4", "x", isIncoming = true)   // plein : refusé
        runCurrent()

        assertEquals(2, r.api.count("request:"))
        assertTrue(r.api.calls.contains("reject:e4"))
        assertFalse(r.api.calls.contains("accept:e4"))

        // Une poignée de main qui échoue libère une place
        r.api.listener!!.onConnectionResult("e1", false)
        r.api.listener!!.onConnectionInitiated("e5", "x", isIncoming = true); runCurrent()
        assertTrue(r.api.calls.contains("accept:e5"))
    }

    @Test
    fun `an incoming proposal is accepted, a requested one is accepted too`() = runTest {
        val r = rig()
        r.transport.start(); runCurrent()
        r.api.listener!!.onConnectionInitiated("in", "x", isIncoming = true)
        r.api.listener!!.onEndpointFound("out", higher)
        r.api.listener!!.onConnectionInitiated("out", "x", isIncoming = false)
        runCurrent()
        assertTrue(r.api.calls.containsAll(listOf("accept:in", "accept:out")))
    }

    // ── Pairs et trames ──────────────────────────────────────────────────────

    @Test
    fun `a peer appears once the connection succeeds and disappears when it drops`() = runTest {
        val r = rig()
        r.transport.start(); runCurrent()

        r.api.listener!!.onConnectionInitiated("e1", "x", true); runCurrent()
        assertTrue("pas encore établie", r.connected().isEmpty())
        r.api.listener!!.onConnectionResult("e1", true); runCurrent()
        assertEquals(listOf("e1"), r.connected())

        r.api.listener!!.onDisconnected("e1"); runCurrent()
        assertEquals(listOf("e1"), r.lost())
        assertEquals(SendResult.PEER_GONE, r.transport.send(peer("e1"), ByteArray(1)))
    }

    @Test
    fun `a failed connection never shows up as a peer, and a second success is not duplicated`() = runTest {
        val r = rig()
        r.transport.start(); runCurrent()
        r.api.listener!!.onConnectionInitiated("bad", "x", true)
        r.api.listener!!.onConnectionResult("bad", false)
        r.api.connect("good")
        r.api.listener!!.onConnectionResult("good", true)    // doublon
        runCurrent()
        assertEquals(listOf("good"), r.connected())
    }

    @Test
    fun `payloads from connected peers become frames, others are ignored`() = runTest {
        val r = rig()
        r.transport.start(); runCurrent()
        r.api.connect("known"); runCurrent()

        r.api.listener!!.onPayload("known", byteArrayOf(1, 2, 3))
        r.api.listener!!.onPayload("stranger", byteArrayOf(9))
        runCurrent()

        val frame = r.frames().single()
        assertEquals(peer("known"), frame.peer)
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.frame)
    }

    @Test
    fun `an oversize payload is dropped and counted`() = runTest {
        val r = rig()
        r.transport.start(); runCurrent()
        r.api.connect("p"); runCurrent()
        r.api.listener!!.onPayload("p", ByteArray(32_769)); runCurrent()
        assertTrue(r.frames().isEmpty())
        assertEquals(1, r.transport.droppedFrames)
    }

    // ── Envoi ────────────────────────────────────────────────────────────────

    @Test
    fun `send results`() = runTest {
        val r = rig()
        r.transport.start(); runCurrent()
        r.api.connect("p"); runCurrent()

        assertEquals(SendResult.SENT, r.transport.send(peer("p"), ByteArray(32_768)))
        assertEquals(SendResult.FRAME_TOO_LARGE, r.transport.send(peer("p"), ByteArray(32_769)))
        assertEquals(SendResult.PEER_GONE, r.transport.send(peer("nobody"), ByteArray(1)))
        assertEquals(SendResult.FAILED, r.transport.send(PeerHandle(TransportId.LORA, "p"), ByteArray(1)))

        r.api.sendFailure = IllegalStateException("link broke")
        assertEquals(SendResult.FAILED, r.transport.send(peer("p"), ByteArray(1)))
        assertEquals(1, r.api.sent.size)
    }

    @Test
    fun `disconnect closes the link on Nearby and forgets the peer`() = runTest {
        val r = rig()
        r.transport.start(); runCurrent()
        r.api.connect("p"); runCurrent()
        r.transport.disconnect(peer("p"))
        assertTrue(r.api.calls.contains("disconnect:p"))
        assertEquals(SendResult.PEER_GONE, r.transport.send(peer("p"), ByteArray(1)))
    }

    // ── Pannes ───────────────────────────────────────────────────────────────

    @Test
    fun `a missing permission makes the transport unavailable and stops the cycles`() = runTest {
        val r = rig()
        r.api.advertiseFailure = NearbyPermissionException("BLUETOOTH_ADVERTISE refused")
        r.transport.start(); runCurrent()

        assertEquals(TransportState.UNAVAILABLE, r.transport.state.value)
        advanceTimeBy(5 * 60_000); runCurrent()
        assertEquals("aucun nouvel essai", 1, r.api.count("advertise:"))
    }

    @Test
    fun `another failure degrades the transport and is retried at the next cycle`() = runTest {
        val r = rig()
        r.api.advertiseFailure = IllegalStateException("radio busy")
        r.transport.start(); runCurrent()
        assertEquals(TransportState.DEGRADED, r.transport.state.value)
        assertEquals("la recherche tourne malgré tout", 1, r.api.count("discover"))

        r.api.advertiseFailure = null
        advanceTimeBy(60_000); runCurrent()
        assertEquals(TransportState.RUNNING, r.transport.state.value)
        assertEquals(2, r.api.count("advertise:"))
    }

    // ── Arrêt ────────────────────────────────────────────────────────────────

    @Test
    fun `stop cancels the cycles, drops every link and detaches from Nearby`() = runTest {
        val r = rig()
        r.transport.start(); runCurrent()
        r.api.connect("a"); r.api.connect("b"); runCurrent()
        val listener = r.api.listener

        r.transport.stop(); runCurrent()
        assertEquals(1, r.api.count("stopAll"))
        assertEquals(null, r.api.listener)
        assertEquals(TransportState.IDLE, r.transport.state.value)
        assertEquals(setOf("a", "b"), r.lost().toSet())

        advanceTimeBy(10 * 60_000); runCurrent()
        assertEquals("plus aucun cycle", 1, r.api.count("advertise:"))
        // Un rappel tardif de Nearby après l'arrêt ne fait rien d'anormal
        listener!!.onPayload("a", byteArrayOf(1)); runCurrent()
        assertTrue(r.frames().isEmpty())
    }

    @Test
    fun `starting twice does not start a second cycle`() = runTest {
        val r = rig()
        r.transport.start(); r.transport.start(); runCurrent()
        assertEquals(1, r.api.count("advertise:"))
    }

    // ── Avec le gestionnaire de transports ───────────────────────────────────

    @Test
    fun `the manager sees Nearby peers as links and rotates them`() = runTest {
        val r = rig()
        val manager = com.netpopup.dtn.transport.TransportManager(
            listOf(r.transport), backgroundScope, clockMillis = { testScheduler.currentTime }, random = Random(4)
        )
        val ups = mutableListOf<com.netpopup.dtn.transport.LinkEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.events.toList(ups) }
        manager.start(); runCurrent()

        r.api.connect("p1"); runCurrent()
        assertEquals(listOf(peer("p1")), manager.links().map { it.peer })

        advanceTimeBy(10.seconds.inWholeMilliseconds * 61); runCurrent()   // > 10 min
        assertTrue(manager.links().isEmpty())
        assertTrue("la rotation a déconnecté le pair côté Nearby", r.api.calls.contains("disconnect:p1"))
    }
}
