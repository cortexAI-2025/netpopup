package com.netpopup.transport.internet

import com.netpopup.dtn.envelope.DestTags
import com.netpopup.dtn.envelope.Envelope
import com.netpopup.dtn.envelope.EnvelopeCodec
import com.netpopup.dtn.envelope.EnvelopeKind
import com.netpopup.dtn.envelope.toBytes
import com.netpopup.dtn.sync.BloomFilter
import com.netpopup.dtn.sync.Frame
import com.netpopup.dtn.sync.FrameCodec
import com.netpopup.dtn.transport.PeerHandle
import com.netpopup.dtn.transport.SendResult
import com.netpopup.dtn.transport.TransportEvent
import com.netpopup.dtn.transport.TransportId
import com.netpopup.dtn.transport.TransportState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class RelayTransportTest {
    private val base = 1_700_000_000_000L
    private val rnd = Random(42)
    private val peer = PeerHandle(TransportId.INTERNET, "relay")

    private val myTagKey = ByteArray(32) { 1 }
    private val otherTagKey = ByteArray(32) { 2 }

    private class Side(val transport: RelayTransport) {
        val events = ArrayList<TransportEvent>()
        val framesToEngine get() = events.filterIsInstance<TransportEvent.FrameReceived>().map { it.frame }
        val inventories get() = framesToEngine.mapNotNull { FrameCodec.decode(it) as? Frame.Inventory }
        val envelopes get() = framesToEngine.mapNotNull { (FrameCodec.decode(it) as? Frame.EnvelopeFrame)?.bytes }
        val connected get() = events.count { it is TransportEvent.PeerConnected }
        val lost get() = events.count { it is TransportEvent.PeerLost }
    }

    private fun TestScope.side(backend: RelayBackend, tagKeys: List<ByteArray> = listOf(myTagKey), config: (RelayConfig) -> RelayConfig = { it }): Side {
        val cfg = config(RelayConfig(tags = { from, to -> tagKeys.flatMap { k -> (from..to).map { DestTags.tag(k, it) } } }))
        val t = RelayTransport(backend, backgroundScope, cfg, { base + currentTime }, rnd)
        return Side(t).also { s ->
            t.events.onEach { s.events += it }.launchIn(backgroundScope)
            testScheduler.runCurrent()   // abonné avant start(), comme l'exige le contrat Transport (flux chaud)
        }
    }

    private fun envelope(tagKey: ByteArray, createdAtSeconds: Long, lifetime: Int = 3_600, seed: Int = 0): ByteArray {
        val e = Envelope(
            EnvelopeKind.DATA, DestTags.forSending(tagKey, createdAtSeconds), createdAtSeconds, lifetime,
            payload = ByteArray(Envelope.MIN_PAYLOAD_SIZE + 8) { (it + seed).toByte() },
        )
        return EnvelopeCodec.encode(e)
    }

    private fun doc(bytes: ByteArray, nowSeconds: Long): RelayDocument {
        val e = (EnvelopeCodec.decode(bytes, nowSeconds) as com.netpopup.dtn.envelope.DecodeResult.Accepted).envelope
        return RelayDocument(e.id.toBytes(), e.destTag, e.createdAt + e.lifetime, bytes)
    }

    private fun emptyInventory(): ByteArray = FrameCodec.encodeInventory(BloomFilter.create(16, 0.001, ByteArray(BloomFilter.SALT_SIZE)))

    private fun nowSeconds(s: TestScope) = (base + s.currentTime) / 1000

    // ── Cycle de vie ─────────────────────────────────────────────────────────

    @Test fun startsAsASinglePeerAndStopsCleanly() = runTest {
        val s = side(InMemoryRelayBackend())
        assertEquals(TransportState.IDLE, s.transport.state.value)
        s.transport.start()
        testScheduler.runCurrent()
        assertEquals(TransportState.RUNNING, s.transport.state.value)
        assertEquals(1, s.connected)
        s.transport.stop()
        testScheduler.runCurrent()
        assertEquals(TransportState.IDLE, s.transport.state.value)
        assertEquals(1, s.lost)
        assertEquals(SendResult.PEER_GONE, s.transport.send(peer, emptyInventory()))
    }

    @Test fun refusesFramesForUnknownPeersAndMalformedFrames() = runTest {
        val backend = InMemoryRelayBackend()
        val s = side(backend)
        s.transport.start()
        assertEquals(SendResult.PEER_GONE, s.transport.send(PeerHandle(TransportId.INTERNET, "other"), emptyInventory()))
        assertEquals(SendResult.FAILED, s.transport.send(peer, byteArrayOf(0x7f, 1, 2)))
        assertEquals(SendResult.FAILED, s.transport.send(peer, FrameCodec.encodeEnvelope(ByteArray(80) { 9 })))
        assertEquals(SendResult.FRAME_TOO_LARGE, s.transport.send(peer, ByteArray(FrameCodec.MAX_FRAME_SIZE + 1)))
        assertEquals("rien n'est rangé sur le relais", 0, backend.count())
    }

    // ── Envoi vers le relais ─────────────────────────────────────────────────

    @Test fun sentIsReturnedOnlyWhenTheRelayStoredTheEnvelope() = runTest {
        val backend = InMemoryRelayBackend()
        val s = side(backend)
        s.transport.start()
        val bytes = envelope(myTagKey, nowSeconds(this))
        assertEquals(SendResult.SENT, s.transport.send(peer, FrameCodec.encodeEnvelope(bytes)))
        assertEquals(1, backend.count())

        backend.offline = true
        val other = envelope(myTagKey, nowSeconds(this), seed = 1)
        assertEquals("relais coupé : pas de « relayé »", SendResult.FAILED, s.transport.send(peer, FrameCodec.encodeEnvelope(other)))
        assertEquals(TransportState.DEGRADED, s.transport.state.value)
        assertEquals(1, backend.count())

        backend.offline = false
        assertEquals(SendResult.SENT, s.transport.send(peer, FrameCodec.encodeEnvelope(other)))
        assertEquals(2, backend.count())
    }

    @Test fun anEnvelopeIsNotPublishedTwice() = runTest {
        val backend = InMemoryRelayBackend()
        val s = side(backend)
        s.transport.start()
        val frame = FrameCodec.encodeEnvelope(envelope(myTagKey, nowSeconds(this)))
        repeat(3) { assertEquals(SendResult.SENT, s.transport.send(peer, frame)) }
        assertEquals(1, backend.publishCalls)
    }

    @Test fun expiredEnvelopesAreNotPublished() = runTest {
        val backend = InMemoryRelayBackend()
        val s = side(backend)
        s.transport.start()
        val old = envelope(myTagKey, nowSeconds(this) - 10 * 3600, lifetime = 3_600)
        assertEquals(SendResult.FAILED, s.transport.send(peer, FrameCodec.encodeEnvelope(old)))
        assertEquals(0, backend.count())
    }

    @Test fun whatTheRelayStoresIsOnlyOpaqueBytesUnderATagWithAnExpiry() = runTest {
        val backend = InMemoryRelayBackend()
        val s = side(backend)
        s.transport.start()
        val now = nowSeconds(this)
        val bytes = envelope(myTagKey, now)
        s.transport.send(peer, FrameCodec.encodeEnvelope(bytes))
        val stored = backend.everything().single()
        assertEquals(16, stored.id.size)
        assertEquals(16, stored.destTag.size)
        assertEquals(now + 3_600, stored.expiresAtSeconds)
        assertTrue("les octets sont exactement l'enveloppe chiffrée reçue du moteur", stored.envelope.contentEquals(bytes))
    }

    @Test fun publicationsPerSessionAreCapped() = runTest {
        val backend = InMemoryRelayBackend()
        val s = side(backend) { RelayConfig(tags = it.tags, maxPublishPerSession = 3) }
        s.transport.start()
        val results = (0 until 5).map { s.transport.send(peer, FrameCodec.encodeEnvelope(envelope(myTagKey, nowSeconds(this), seed = it))) }
        assertEquals(listOf(SendResult.SENT, SendResult.SENT, SendResult.SENT, SendResult.FAILED, SendResult.FAILED), results)
    }

    // ── Réception depuis le relais ───────────────────────────────────────────

    @Test fun answersTheEnginesInventoryWithItsOwnAndPushesWhatTheEngineLacks() = runTest {
        val backend = InMemoryRelayBackend()
        val now = nowSeconds(this)
        val wanted = (0 until 3).map { envelope(myTagKey, now, seed = it) }
        wanted.forEach { backend.publish(doc(it, now)) }
        val s = side(backend)
        s.transport.start()

        assertEquals(SendResult.SENT, s.transport.send(peer, emptyInventory()))
        advanceTimeBy(1_000)

        assertEquals("un seul INVENTORY en réponse", 1, s.inventories.size)
        assertEquals(3, s.envelopes.size)
        assertTrue(wanted.all { w -> s.envelopes.any { it.contentEquals(w) } })
        // L'INVENTORY du relais contient ce qu'il a : le moteur ne lui renverra pas ces enveloppes.
        wanted.forEach { w -> assertTrue(s.inventories.single().filter.mightContain(doc(w, now).id)) }
    }

    @Test fun doesNotPushWhatTheEngineAlreadyHas() = runTest {
        val backend = InMemoryRelayBackend()
        val now = nowSeconds(this)
        val known = envelope(myTagKey, now, seed = 1)
        val fresh = envelope(myTagKey, now, seed = 2)
        listOf(known, fresh).forEach { backend.publish(doc(it, now)) }
        val s = side(backend)
        s.transport.start()

        val engineFilter = BloomFilter.create(16, 1e-6, ByteArray(BloomFilter.SALT_SIZE)).also { it.add(doc(known, now).id) }
        s.transport.send(peer, FrameCodec.encodeInventory(engineFilter))
        advanceTimeBy(1_000)
        assertEquals(listOf(fresh.toList()), s.envelopes.map { it.toList() })
    }

    @Test fun onlyListenedTagsAreFetchedAndForeignEnvelopesStayUntouched() = runTest {
        val backend = InMemoryRelayBackend()
        val now = nowSeconds(this)
        backend.publish(doc(envelope(otherTagKey, now), now))     // conversation d'un autre
        val s = side(backend)
        s.transport.start()
        s.transport.send(peer, emptyInventory())
        advanceTimeBy(1_000)
        assertTrue("rien n'est livré pour une étiquette non écoutée", s.envelopes.isEmpty())
        val ours = (DestTags.epochOf(now) - 26..DestTags.epochOf(now) + 1).map { DestTags.tag(myTagKey, it).toHex() }.toSet()
        assertTrue("seules nos étiquettes sont interrogées", backend.fetchedTags.all { it in ours })
        assertEquals(ours.size, backend.fetchedTags.toSet().size)
    }

    @Test fun expiredDocumentsAreIgnored() = runTest {
        val backend = InMemoryRelayBackend()
        val now = nowSeconds(this)
        backend.publish(doc(envelope(myTagKey, now - 7_200, lifetime = 7_300), now - 7_200))   // expire dans 100 s
        val s = side(backend)
        s.transport.start()
        advanceTimeBy(200_000)                                  // plus de 100 s plus tard
        s.transport.send(peer, emptyInventory())
        advanceTimeBy(1_000)
        assertTrue(s.envelopes.isEmpty())
    }

    @Test fun pushesAreFlowControlledBelowTheEnginesTokenBucket() = runTest {
        val backend = InMemoryRelayBackend()
        val now = nowSeconds(this)
        repeat(40) { backend.publish(doc(envelope(myTagKey, now, seed = it), now)) }
        val s = side(backend)
        s.transport.start()
        s.transport.send(peer, emptyInventory())
        val start = currentTime
        advanceTimeBy(100)
        assertEquals("rafale initiale", 18, s.envelopes.size)
        advanceTimeBy(5_000)
        assertTrue("puis environ 1 par seconde, jamais plus", s.envelopes.size in 20..24)
        advanceTimeBy(40_000)
        assertEquals(40, s.envelopes.size)
        assertTrue(currentTime - start > 20_000)
    }

    @Test fun pollingBringsNewEnvelopesDuringTheSession() = runTest {
        val backend = InMemoryRelayBackend()
        val s = side(backend)
        s.transport.start()
        s.transport.send(peer, emptyInventory())
        advanceTimeBy(1_000)
        assertTrue(s.envelopes.isEmpty())

        val now = nowSeconds(this)
        val later = envelope(myTagKey, now, seed = 7)
        backend.publish(doc(later, now))                       // un autre téléphone vient de l'y ranger
        advanceTimeBy(121_000)
        assertEquals(listOf(later.toList()), s.envelopes.map { it.toList() })
        advanceTimeBy(300_000)
        assertEquals("jamais deux fois dans la même session", 1, s.envelopes.size)
    }

    // ── Pannes et rotation ───────────────────────────────────────────────────

    @Test fun unreachableRelayClosesTheLinkThenReopensIt() = runTest {
        val backend = InMemoryRelayBackend()
        val s = side(backend)
        s.transport.start()
        backend.offline = true
        s.transport.send(peer, emptyInventory())
        advanceTimeBy(1_000)
        assertEquals(1, s.lost)
        assertEquals(TransportState.DEGRADED, s.transport.state.value)
        assertEquals("aucune trame inventée", 0, s.inventories.size)

        backend.offline = false
        advanceTimeBy(71_000)
        assertEquals(2, s.connected)
        s.transport.send(peer, emptyInventory())
        advanceTimeBy(1_000)
        assertEquals(1, s.inventories.size)
        assertEquals(TransportState.RUNNING, s.transport.state.value)
    }

    @Test fun rotationOpensAFreshSessionAfterTheReconnectDelay() = runTest {
        val s = side(InMemoryRelayBackend())
        s.transport.start()
        s.transport.send(peer, emptyInventory())
        s.transport.disconnect(peer)
        testScheduler.runCurrent()
        assertEquals(1, s.lost)
        assertEquals(SendResult.PEER_GONE, s.transport.send(peer, emptyInventory()))
        advanceTimeBy(69_000)
        assertEquals(1, s.connected)
        advanceTimeBy(2_000)
        assertEquals(2, s.connected)
        assertEquals("nouvelle session : un nouvel INVENTORY est accepté", SendResult.SENT, s.transport.send(peer, emptyInventory()))
    }

    @Test fun aSecondInventoryInTheSameSessionIsRefused() = runTest {
        val s = side(InMemoryRelayBackend())
        s.transport.start()
        assertEquals(SendResult.SENT, s.transport.send(peer, emptyInventory()))
        assertEquals(SendResult.FAILED, s.transport.send(peer, emptyInventory()))
    }

    @Test fun stoppingStopsEverythingForGood() = runTest {
        val backend = InMemoryRelayBackend()
        val s = side(backend)
        s.transport.start()
        s.transport.send(peer, emptyInventory())
        advanceTimeBy(1_000)
        s.transport.stop()
        val fetchesAtStop = backend.fetchedTags.size
        advanceTimeBy(600_000)
        assertEquals("plus aucune relève après l'arrêt", fetchesAtStop, backend.fetchedTags.size)
        assertEquals("ne se rouvre pas tout seul", 1, s.connected)
    }

    @Test fun fullSweepOnlyOncePerInterval() = runTest {
        val backend = InMemoryRelayBackend()
        val s = side(backend)
        s.transport.start()
        s.transport.send(peer, emptyInventory())
        advanceTimeBy(1_000)
        val first = backend.fetchedTags.size                                   // balayage complet : 26 époques en arrière + courante + suivante
        assertEquals(28, first)
        s.transport.disconnect(peer)
        advanceTimeBy(72_000)
        s.transport.send(peer, emptyInventory())
        advanceTimeBy(1_000)
        val second = backend.fetchedTags.size - first
        assertTrue("la 2e session ne relève que les époques récentes (3), pas 28 : $second", second <= 8)
        assertFalse(second == 0)
    }
}
