package com.netpopup.transport.nearby

import com.netpopup.dtn.transport.CostClass
import com.netpopup.dtn.transport.PeerHandle
import com.netpopup.dtn.transport.SendResult
import com.netpopup.dtn.transport.Transport
import com.netpopup.dtn.transport.TransportCapabilities
import com.netpopup.dtn.transport.TransportEvent
import com.netpopup.dtn.transport.TransportId
import com.netpopup.dtn.transport.TransportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Transport sur Google Nearby Connections, stratégie `P2P_CLUSTER`
 * (architecture mesh v0.2, section 1). Kotlin pur : tout Nearby passe par [NearbyApi].
 *
 * **Anonymat.** Le nom diffusé est un hexadécimal aléatoire de 8 caractères, renouvelé à
 * chaque cycle : ni pseudo, ni clé publique. Les connexions sont acceptées d'office,
 * car l'authentification et le chiffrement se font au-dessus (NetPopUp), pas par le code
 * PIN de Nearby.
 *
 * **Cycles.** Diffusion et recherche tournent [NearbyConfig.activeDuration], puis s'arrêtent
 * [NearbyConfig.idleDuration] pour économiser la batterie ; les connexions déjà établies
 * survivent aux pauses.
 *
 * **Qui demande la connexion.** Les deux téléphones se voient en même temps : si les deux
 * demandaient, Nearby verrait deux connexions concurrentes. Seul celui dont le nom est
 * le plus petit (ordre lexicographique) la demande, l'autre attend. Les noms changeant à
 * chaque cycle, deux appareils peuvent parfois ne pas s'accorder : aucun ne demande, ou
 * les deux (Nearby tolère le doublon, [handshaking] l'écarte) ; le cycle suivant
 * rattrape.
 *
 * **Plafond.** Au plus [NearbyConfig.maxConnections] liens, poignées de main en cours
 * comprises ; au-delà, la connexion proposée est refusée.
 *
 * Les rappels de Nearby arrivent sur n'importe quel thread : l'état partagé est protégé
 * par [lock] et les événements sont émis sans suspendre. Un tampon plein perd la trame
 * ([droppedFrames]) : le transport est de toute façon sans garantie.
 */
class NearbyTransport(
    private val api: NearbyApi,
    private val scope: CoroutineScope,
    private val config: NearbyConfig = NearbyConfig(),
    private val random: Random = Random.Default
) : Transport {

    override val id: TransportId = TransportId.NEARBY

    override val capabilities = TransportCapabilities(
        maxFrameBytes = NearbyConfig.MAX_FRAME_BYTES,
        maxConcurrentPeers = config.maxConnections,
        isBroadcastOnly = false,
        costClass = CostClass.FREE
    )

    private val _state = MutableStateFlow(TransportState.IDLE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 256)
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    private val _diagnostics = MutableStateFlow(NearbyDiagnostics())
    val diagnostics: StateFlow<NearbyDiagnostics> = _diagnostics.asStateFlow()

    private val lock = Any()
    private var localName = ""
    private val connected = LinkedHashSet<String>()
    private val handshaking = HashSet<String>()
    private val seen = LinkedHashMap<String, String>()
    private val timeouts = HashMap<String, Job>()
    private var cycle: Job? = null
    private val dropped = AtomicInteger()
    private val cycleCount = AtomicInteger()

    /** Trames perdues : trop grandes, ou tampon d'événements plein. */
    val droppedFrames: Int get() = dropped.get()

    /** Nombre de cycles de diffusion lancés depuis le démarrage. */
    val cycles: Int get() = cycleCount.get()

    private enum class Phase { OK, DEGRADED, PERMISSION_DENIED }

    override suspend fun start() {
        if (cycle?.isActive == true) return
        api.setListener(listener)
        cycle = scope.launch { runCycles() }
    }

    override suspend fun stop() {
        cycle?.cancelAndJoin()
        cycle = null
        val gone = synchronized(lock) {
            val peers = connected.toList()
            connected.clear()
            handshaking.clear()
            seen.clear()
            timeouts.values.forEach { it.cancel() }
            timeouts.clear()
            peers
        }
        quietly { api.stopAll() }
        api.setListener(null)
        gone.forEach { _events.tryEmit(TransportEvent.PeerLost(handle(it))) }
        _state.value = TransportState.IDLE
        _diagnostics.value = NearbyDiagnostics()
    }

    override suspend fun send(peer: PeerHandle, frame: ByteArray): SendResult {
        if (peer.transport != id) return SendResult.FAILED
        if (frame.size > NearbyConfig.MAX_FRAME_BYTES) return SendResult.FRAME_TOO_LARGE
        if (!synchronized(lock) { peer.value in connected }) return SendResult.PEER_GONE
        return try {
            api.sendBytes(peer.value, frame)
            SendResult.SENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SendResult.FAILED
        }
    }

    override suspend fun disconnect(peer: PeerHandle) {
        synchronized(lock) {
            connected.remove(peer.value)
            handshaking.remove(peer.value)
            timeouts.remove(peer.value)?.cancel()
        }
        refreshCounts()
        quietly { api.disconnect(peer.value) }
        retryEndpoint(peer.value)
    }

    // ── Cycles de diffusion ──────────────────────────────────────────────────

    private suspend fun runCycles() {
        while (true) {
            val name = newName()
            synchronized(lock) { localName = name }
            cycleCount.incrementAndGet()

            when (startPhase(name)) {
                Phase.PERMISSION_DENIED -> {
                    // Inutile de réessayer tant que l'utilisateur n'a pas accordé la permission
                    quietly { api.stopAll() }
                    _state.value = TransportState.UNAVAILABLE
                    return
                }
                Phase.DEGRADED -> _state.value = TransportState.DEGRADED
                Phase.OK -> {
                    _state.value = TransportState.RUNNING
                    // Stop/restart rotates names mid-handshake and fixed duty windows may never overlap.
                    if (config.continuous) awaitCancellation()
                }
            }

            delay(config.activeDuration)
            quietly { api.stopAdvertising() }
            quietly { api.stopDiscovery() }
            _diagnostics.update { it.copy(advertising = false, discovering = false) }
            if (!config.continuous && config.idleDuration > kotlin.time.Duration.ZERO) delay(config.idleDuration)
        }
    }

    private suspend fun startPhase(name: String): Phase {
        var failures = 0
        val steps: List<Pair<String, suspend () -> Unit>> = listOf(
            "Advertising" to suspend { api.startAdvertising(name) },
            "Discovery" to suspend { api.startDiscovery() }
        )
        for ((operation, step) in steps) {
            try {
                step()
                _diagnostics.update {
                    if (operation == "Advertising") it.copy(advertising = true) else it.copy(discovering = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NearbyPermissionException) {
                reportFailure(operation, e)
                quietly { api.stopAdvertising() }
                quietly { api.stopDiscovery() }
                _diagnostics.update { it.copy(advertising = false, discovering = false) }
                return Phase.PERMISSION_DENIED
            } catch (e: Exception) {
                reportFailure(operation, e)
                failures++
            }
        }
        if (failures == 0) _diagnostics.update { it.copy(lastFailure = null) }
        return if (failures == 0) Phase.OK else Phase.DEGRADED
    }

    private fun newName(): String =
        ByteArray(4).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    // ── Rappels de Nearby ────────────────────────────────────────────────────

    private val listener = object : NearbyListener {

        override fun onEndpointFound(endpointId: String, remoteName: String) {
            val name = synchronized(lock) {
                seen[endpointId] = remoteName
                val canRequest = endpointId !in connected &&
                    endpointId !in handshaking &&
                    connected.size + handshaking.size < config.maxConnections &&
                    localName.isNotEmpty() &&
                    localName < remoteName
                if (canRequest) handshaking += endpointId
                if (canRequest) localName else null
            }
            refreshCounts()
            if (name == null) return
            armTimeout(endpointId)
            scope.launch {
                try {
                    api.requestConnection(name, endpointId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    freeHandshake(endpointId)
                    reportFailure("Connection request", e)
                    retryEndpoint(endpointId)
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            synchronized(lock) { seen.remove(endpointId) }
            refreshCounts()
        }

        override fun onConnectionInitiated(endpointId: String, remoteName: String, isIncoming: Boolean) {
            val accept = synchronized(lock) {
                when {
                    endpointId in connected -> false
                    endpointId in handshaking -> true            // c'est nous qui l'avons demandée
                    connected.size + handshaking.size < config.maxConnections -> {
                        handshaking += endpointId
                        true
                    }
                    else -> false
                }
            }
            refreshCounts()
            if (accept) armTimeout(endpointId)
            scope.launch {
                try {
                    if (accept) api.acceptConnection(endpointId) else api.rejectConnection(endpointId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    freeHandshake(endpointId)
                    reportFailure("Connection acceptance", e)
                    retryEndpoint(endpointId)
                }
            }
        }

        override fun onConnectionFailure(endpointId: String, statusCode: Int) {
            _diagnostics.update { it.copy(lastFailure = "Connection: Nearby status $statusCode") }
        }

        override fun onConnectionResult(endpointId: String, success: Boolean) {
            val opened = synchronized(lock) {
                timeouts.remove(endpointId)?.cancel()
                val pending = handshaking.remove(endpointId)
                success && pending && connected.add(endpointId)
            }
            refreshCounts()
            if (opened) {
                _diagnostics.update { it.copy(lastFailure = null) }
                emit(TransportEvent.PeerConnected(handle(endpointId)))
            } else if (!success) retryEndpoint(endpointId)
        }

        override fun onDisconnected(endpointId: String) {
            val wasConnected = synchronized(lock) {
                handshaking -= endpointId
                timeouts.remove(endpointId)?.cancel()
                connected.remove(endpointId)
            }
            refreshCounts()
            if (wasConnected) {
                emit(TransportEvent.PeerLost(handle(endpointId)))
                retryEndpoint(endpointId)
            }
        }

        override fun onPayload(endpointId: String, bytes: ByteArray) {
            // Une charge utile d'un pair sans connexion établie est ignorée
            if (!synchronized(lock) { endpointId in connected }) return
            if (bytes.size > NearbyConfig.MAX_FRAME_BYTES) {
                dropped.incrementAndGet()
                return
            }
            emit(TransportEvent.FrameReceived(handle(endpointId), bytes))
        }
    }

    private fun refreshCounts() {
        synchronized(lock) {
            _diagnostics.update { it.copy(discovered = seen.size, connecting = handshaking.size, connected = connected.size) }
        }
    }

    private fun reportFailure(operation: String, error: Exception) {
        val reason = if (error is NearbyApiException) "Nearby status ${error.statusCode}" else error.javaClass.simpleName
        _diagnostics.update { it.copy(lastFailure = "$operation: $reason") }
    }

    private fun freeHandshake(endpointId: String) {
        synchronized(lock) {
            handshaking.remove(endpointId)
            timeouts.remove(endpointId)?.cancel()
        }
        refreshCounts()
    }

    private fun armTimeout(endpointId: String) {
        val job = scope.launch {
            delay(config.connectionTimeout)
            val expired = synchronized(lock) {
                timeouts.remove(endpointId)
                handshaking.remove(endpointId)
            }
            if (expired) {
                _diagnostics.update { it.copy(lastFailure = "Connection timed out; discovery is still running") }
                refreshCounts()
                quietly { api.disconnect(endpointId) }
                retryEndpoint(endpointId)
            }
        }
        synchronized(lock) { timeouts.put(endpointId, job)?.cancel() }
    }

    private fun retryEndpoint(endpointId: String) {
        scope.launch {
            delay(2_000)
            val remote = synchronized(lock) { if (cycle?.isActive == true) seen[endpointId] else null }
            if (remote != null) listener.onEndpointFound(endpointId, remote)
        }
    }

    private fun emit(event: TransportEvent) {
        if (!_events.tryEmit(event)) dropped.incrementAndGet()
    }

    private fun handle(endpointId: String) = PeerHandle(id, endpointId)

    private suspend inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nettoyage au mieux : l'échec d'un arrêt ne doit jamais en empêcher un autre
        }
    }
}
