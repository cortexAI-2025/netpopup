package com.netpopup.dtn.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class TransportManagerConfig(
    /** Plafond global de liens simultanés, tous transports confondus (3 à 4 en v0). */
    val maxConnections: Int = 4,
    /** Un lien est fermé après une durée tirée au hasard entre [rotationMin] et [rotationMax]. */
    val rotationMin: Duration = 5.minutes,
    val rotationMax: Duration = 10.minutes,
    /** Après une rotation, la même poignée n'est pas réacceptée pendant ce délai. */
    val reconnectCooldown: Duration = 60.seconds
) {
    init {
        require(maxConnections >= 1) { "maxConnections must be >= 1" }
        require(rotationMin > Duration.ZERO) { "rotationMin must be > 0" }
        require(rotationMax >= rotationMin) { "rotationMax must be >= rotationMin" }
        require(!reconnectCooldown.isNegative()) { "reconnectCooldown must be >= 0" }
    }
}

enum class LinkDownReason {
    /** Le transport a signalé la perte du pair, ou un envoi a constaté son absence. */
    LOST,

    /** Fermé par la rotation, pour laisser la place à d'autres pairs. */
    ROTATED,

    /** Le transport s'est arrêté ou est devenu indisponible. */
    TRANSPORT_STOPPED
}

/** Ce que voit le cœur DTN : des liens qui s'ouvrent, se ferment, et des trames. */
sealed interface LinkEvent {
    data class LinkUp(val peer: PeerHandle, val capabilities: TransportCapabilities) : LinkEvent
    data class LinkDown(val peer: PeerHandle, val reason: LinkDownReason) : LinkEvent
    class FrameReceived(val peer: PeerHandle, val frame: ByteArray) : LinkEvent
}

data class LinkInfo(
    val peer: PeerHandle,
    val costClass: CostClass,
    val connectedAtMillis: Long,
    /** Durée d'envoi moyenne observée sur ce transport, null tant qu'aucun envoi n'a abouti. */
    val latencyMillis: Double?
)

/**
 * Agrège les transports actifs et présente au cœur DTN une seule vue « liens ouverts »
 * (architecture mesh v0.1, section 1).
 *
 *  - démarre tous les transports ; l'échec de l'un n'empêche pas les autres ;
 *  - **plafond global** de [TransportManagerConfig.maxConnections] liens : un pair de
 *    plus est déconnecté aussitôt ;
 *  - **rotation** : chaque lien est fermé après une durée aléatoire de 5 à 10 minutes,
 *    tirée séparément pour chacun, pour que les rotations d'un groupe ne se synchronisent
 *    pas. Avec 3 ou 4 places, c'est ce qui laisse les messages traverser un groupe dense ;
 *  - **délai de reconnexion** : une poignée tout juste tournée n'est pas réacceptée
 *    tout de suite, sinon elle reprendrait la place qu'elle vient de libérer ;
 *  - les pairs restent anonymes : aucune tentative de reconnaître un même téléphone sur
 *    deux transports, la déduplication par `envelopeId` rend un double échange sans effet.
 *
 * Un transport Internet futur s'ajoute, il ne remplace jamais le mesh : [links] et
 * [broadcast] ordonnent les liens par coût (gratuit d'abord) puis par durée d'envoi observée.
 *
 * Sûr à appeler depuis plusieurs coroutines. Le [scope] porte les collecteurs d'événements
 * et les minuteurs de rotation.
 */
class TransportManager(
    private val transports: List<Transport>,
    private val scope: CoroutineScope,
    val config: TransportManagerConfig = TransportManagerConfig(),
    private val random: Random = Random.Default,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    private class Link(
        val peer: PeerHandle,
        val capabilities: TransportCapabilities,
        val connectedAt: Long,
        val rotation: Job
    )

    private val mutex = Mutex()
    private val links = LinkedHashMap<PeerHandle, Link>()
    private val cooldownUntil = HashMap<PeerHandle, Long>()
    private val latencyMillis = HashMap<TransportId, Double>()
    private val collectors = mutableListOf<Job>()
    private val byId = transports.associateBy { it.id }
    private val _events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 256)

    val events: SharedFlow<LinkEvent> = _events.asSharedFlow()

    /** Connexions refusées (plafond atteint, délai de reconnexion, doublon). */
    var rejectedConnections: Int = 0
        private set

    init {
        require(byId.size == transports.size) { "two transports share the same id" }
    }

    /** Démarre les transports ; renvoie ceux qui n'ont pas pu démarrer. */
    suspend fun start(): List<TransportId> {
        val failed = mutableListOf<TransportId>()
        for (transport in transports) {
            // UNDISPATCHED : on est abonné avant que le transport ne se mette à émettre
            collectors += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.events.collect { onEvent(transport, it) }
            }
            collectors += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.state.collect { state ->
                    if (state == TransportState.UNAVAILABLE || state == TransportState.IDLE) {
                        dropLinks(transport.id, LinkDownReason.TRANSPORT_STOPPED)
                    }
                }
            }
            try {
                transport.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += transport.id
            }
        }
        return failed
    }

    suspend fun stop() {
        collectors.forEach(Job::cancel)
        collectors.clear()
        transports.forEach { transport -> runCatching { transport.stop() } }
        transports.forEach { dropLinks(it.id, LinkDownReason.TRANSPORT_STOPPED) }
    }

    /** Liens ouverts, du moins cher au plus cher, puis du plus rapide au plus lent. */
    suspend fun links(): List<LinkInfo> = mutex.withLock {
        links.values.map {
            LinkInfo(it.peer, it.capabilities.costClass, it.connectedAt, latencyMillis[it.peer.transport])
        }.sortedWith(
            compareBy<LinkInfo> { it.costClass.ordinal }
                .thenBy { it.latencyMillis ?: Double.MAX_VALUE }
                .thenBy { it.connectedAtMillis }
        )
    }

    suspend fun send(peer: PeerHandle, frame: ByteArray): SendResult {
        val link = mutex.withLock { links[peer] } ?: return SendResult.PEER_GONE
        if (frame.size > link.capabilities.maxFrameBytes) return SendResult.FRAME_TOO_LARGE
        val transport = byId.getValue(peer.transport)

        val startedAt = clockMillis()
        val result = transport.send(peer, frame)
        when (result) {
            SendResult.SENT -> recordLatency(peer.transport, clockMillis() - startedAt)
            SendResult.PEER_GONE -> removeLink(peer, LinkDownReason.LOST)
            else -> Unit
        }
        return result
    }

    /** Envoie [frame] sur tous les liens, dans l'ordre de [links]. */
    suspend fun broadcast(frame: ByteArray): Map<PeerHandle, SendResult> =
        links().associate { it.peer to send(it.peer, frame) }

    // ── Événements des transports ────────────────────────────────────────────

    private suspend fun onEvent(transport: Transport, event: TransportEvent) {
        when (event) {
            is TransportEvent.PeerConnected -> onPeerConnected(transport, event.peer)
            is TransportEvent.PeerLost -> removeLink(event.peer, LinkDownReason.LOST)
            is TransportEvent.FrameReceived -> {
                // Une trame d'un pair sans lien (refusé, tourné, perdu) est ignorée
                val known = mutex.withLock { event.peer in links }
                if (known) _events.emit(LinkEvent.FrameReceived(event.peer, event.frame))
            }
        }
    }

    private suspend fun onPeerConnected(transport: Transport, peer: PeerHandle) {
        val accepted = mutex.withLock {
            val now = clockMillis()
            cooldownUntil.values.removeAll { it <= now }
            val refused = peer in links ||
                links.size >= config.maxConnections ||
                (cooldownUntil[peer] ?: 0L) > now
            if (refused) {
                rejectedConnections++
                false
            } else {
                links[peer] = Link(peer, transport.capabilities, now, scope.launch {
                    delay(nextRotationDelay())
                    rotate(peer)
                })
                true
            }
        }
        if (accepted) {
            _events.emit(LinkEvent.LinkUp(peer, transport.capabilities))
        } else {
            runCatching { transport.disconnect(peer) }
        }
    }

    private fun nextRotationDelay(): Duration {
        val minMillis = config.rotationMin.inWholeMilliseconds
        val spread = config.rotationMax.inWholeMilliseconds - minMillis
        return (minMillis + random.nextLong(spread + 1)).milliseconds
    }

    private suspend fun rotate(peer: PeerHandle) {
        val removed = mutex.withLock {
            val link = links.remove(peer) ?: return
            cooldownUntil[peer] = clockMillis() + config.reconnectCooldown.inWholeMilliseconds
            link
        }
        _events.emit(LinkEvent.LinkDown(removed.peer, LinkDownReason.ROTATED))
        runCatching { byId.getValue(peer.transport).disconnect(peer) }
    }

    private suspend fun removeLink(peer: PeerHandle, reason: LinkDownReason) {
        val removed = mutex.withLock { links.remove(peer) } ?: return
        removed.rotation.cancel()
        _events.emit(LinkEvent.LinkDown(removed.peer, reason))
    }

    private suspend fun dropLinks(id: TransportId, reason: LinkDownReason) {
        val removed = mutex.withLock {
            val gone = links.values.filter { it.peer.transport == id }
            gone.forEach { links.remove(it.peer) }
            gone
        }
        removed.forEach {
            it.rotation.cancel()
            _events.emit(LinkEvent.LinkDown(it.peer, reason))
        }
    }

    private suspend fun recordLatency(id: TransportId, millis: Long) = mutex.withLock {
        val previous = latencyMillis[id]
        // Moyenne mobile : un envoi lent isolé ne fait pas basculer le classement
        latencyMillis[id] = if (previous == null) millis.toDouble() else previous * 0.8 + millis * 0.2
    }
}
