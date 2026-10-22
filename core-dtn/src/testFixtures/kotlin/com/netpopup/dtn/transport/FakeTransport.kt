package com.netpopup.dtn.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Transport en mémoire, pour les tests du [TransportManager] et le simulateur.
 *
 * Les méthodes `simulate*` jouent la radio : un pair apparaît, disparaît, envoie une trame.
 * Tout ce que le cœur lui demande (envois, déconnexions) est enregistré.
 */
class FakeTransport(
    override val id: TransportId = TransportId.NEARBY,
    override val capabilities: TransportCapabilities = TransportCapabilities(
        maxFrameBytes = 32_768, maxConcurrentPeers = 4, isBroadcastOnly = false, costClass = CostClass.FREE
    ),
    private val startFails: Boolean = false,
    /** Durée simulée de chaque envoi (temps virtuel dans les tests), pour mesurer la latence. */
    private val sendDelayMillis: Long = 0
) : Transport {

    private val _state = MutableStateFlow(TransportState.IDLE)
    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 256)

    override val state: StateFlow<TransportState> = _state.asStateFlow()
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    private val connected = LinkedHashSet<PeerHandle>()

    class SentFrame(val peer: PeerHandle, val frame: ByteArray)

    val sent = mutableListOf<SentFrame>()
    val disconnected = mutableListOf<PeerHandle>()

    /** Résultat des prochains envois à un pair connecté. */
    var sendResult: SendResult = SendResult.SENT

    var startCount = 0
        private set
    var stopCount = 0
        private set

    override suspend fun start() {
        startCount++
        if (startFails) throw IllegalStateException("radio unavailable")
        _state.value = TransportState.RUNNING
    }

    override suspend fun stop() {
        stopCount++
        _state.value = TransportState.IDLE
        connected.clear()
    }

    override suspend fun send(peer: PeerHandle, frame: ByteArray): SendResult {
        if (peer !in connected) return SendResult.PEER_GONE
        if (sendDelayMillis > 0) delay(sendDelayMillis)
        sent += SentFrame(peer, frame)
        return sendResult
    }

    override suspend fun disconnect(peer: PeerHandle) {
        connected.remove(peer)
        disconnected += peer
    }

    // ── Simulation de la radio ───────────────────────────────────────────────

    fun peer(name: String) = PeerHandle(id, name)

    fun simulatePeerConnected(peer: PeerHandle) {
        connected += peer
        _events.tryEmit(TransportEvent.PeerConnected(peer))
    }

    fun simulatePeerLost(peer: PeerHandle) {
        connected -= peer
        _events.tryEmit(TransportEvent.PeerLost(peer))
    }

    fun simulateFrame(peer: PeerHandle, frame: ByteArray) {
        _events.tryEmit(TransportEvent.FrameReceived(peer, frame))
    }

    fun simulateState(state: TransportState) {
        _state.value = state
    }
}
