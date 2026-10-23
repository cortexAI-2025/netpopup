package com.netpopup.transport.internet

import com.netpopup.dtn.envelope.DecodeResult
import com.netpopup.dtn.envelope.DestTags
import com.netpopup.dtn.envelope.EnvelopeCodec
import com.netpopup.dtn.envelope.toBytes
import com.netpopup.dtn.sync.BloomFilter
import com.netpopup.dtn.sync.Frame
import com.netpopup.dtn.sync.FrameCodec
import com.netpopup.dtn.transport.CostClass
import com.netpopup.dtn.transport.PeerHandle
import com.netpopup.dtn.transport.SendResult
import com.netpopup.dtn.transport.Transport
import com.netpopup.dtn.transport.TransportCapabilities
import com.netpopup.dtn.transport.TransportEvent
import com.netpopup.dtn.transport.TransportId
import com.netpopup.dtn.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RelayConfig(
    /**
     * Étiquettes à relever pour les époques [fromEpoch]..[toEpoch], fournies par le moteur
     * (`MeshEngine.listeningTags`). Le transport ne reçoit jamais les clés qui les produisent.
     */
    val tags: suspend (fromEpoch: Long, toEpoch: Long) -> List<ByteArray>,
    /**
     * Relève courante : de l'époque actuelle moins ceci jusqu'à l'époque suivante (dérive d'horloge), soit 3 époques par
     * étiquette. Ce qui est plus ancien arrive au balayage complet (au plus [fullSweepInterval] plus tard).
     */
    val recentEpochsBack: Int = 1,
    /** Balayage complet : durée de vie maximale (24 h) plus dérive d'horloge (2 h), avec marge. */
    val maxAgeEpochs: Int = 26,
    /** Une lecture Firestore par étiquette et par époque : 120 s garde un téléphone sous ~16 000 lectures par jour (voir docs/internet-relay.md). */
    val pollInterval: Duration = 120.seconds,
    /** Entre deux balayages complets, seules les époques récentes sont relevées (économie de lectures). */
    val fullSweepInterval: Duration = 30.minutes,
    /** Après une fermeture de lien (rotation, panne), délai avant de rouvrir : supérieur au délai de reconnexion du gestionnaire. */
    val reconnectDelay: Duration = 70.seconds,
    val maxDocsPerTag: Int = 50,
    /** Enveloppes poussées au moteur sans pause, puis une par [pushIntervalMillis] : sous le seau à jetons du moteur (20, puis 1/s). */
    val pushBurst: Int = 18,
    val pushIntervalMillis: Long = 1_100,
    /** Plafond d'écritures par session : un téléphone qui porte des milliers d'enveloppes ne vide pas le quota du relais. */
    val maxPublishPerSession: Int = 200,
    /** Relèves en parallèle. */
    val fetchParallelism: Int = 4,
) {
    init {
        require(recentEpochsBack >= 0 && maxAgeEpochs >= recentEpochsBack) { "invalid epoch window" }
        require(pushBurst in 1..20) { "pushBurst must be in 1..20 (engine burst is 20)" }
        require(pushIntervalMillis >= 1_000) { "pushIntervalMillis must be >= 1000 (engine refills 1/s)" }
        require(fetchParallelism >= 1 && maxDocsPerTag >= 1 && maxPublishPerSession >= 0) { "invalid limits" }
    }
}

/**
 * Le relais Internet vu comme un [Transport] : un seul « pair », toujours le même, qui parle le protocole de
 * synchronisation du mesh (`INVENTORY`, `ENVELOPE`) alors que de l'autre côté se trouve un simple magasin.
 *
 * - À l'`INVENTORY` du moteur, il relève sur le relais les enveloppes des étiquettes que le moteur écoute, répond par
 *   son propre `INVENTORY` (ce que le relais contient) puis pousse ce que le moteur n'a pas.
 * - À chaque `ENVELOPE` du moteur, il la range sur le relais ; **`SENT` n'est renvoyé que si le relais l'a acceptée**,
 *   pour que « relayé » ne soit jamais affiché avant que ce soit vrai.
 * - Pendant la session, il relève périodiquement les époques récentes.
 *
 * Le relais ne reçoit que des octets déjà chiffrés rangés sous une étiquette qui tourne chaque heure. Ce module ne
 * connaît ni clé, ni identité, ni conversation. Coût : [CostClass.METERED], donc le gestionnaire préfère Nearby.
 */
class RelayTransport(
    private val backend: RelayBackend,
    private val scope: CoroutineScope,
    private val config: RelayConfig,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) : Transport {

    override val id = TransportId.INTERNET
    override val capabilities = TransportCapabilities(
        maxFrameBytes = FrameCodec.MAX_FRAME_SIZE, maxConcurrentPeers = 1, isBroadcastOnly = false, costClass = CostClass.METERED,
    )

    private val _state = MutableStateFlow(TransportState.IDLE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 1_024)
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    /** Le seul pair : le relais. Sans identité NetPopUp, comme tout pair. */
    private val peer = PeerHandle(TransportId.INTERNET, "relay")

    private class Session {
        val delivered = HashSet<String>()
        var published = 0
        var inventoryHandled = false
        val jobs = ArrayList<Job>()
    }

    private val lock = Mutex()
    private var started = false
    private var session: Session? = null
    private var reconnectJob: Job? = null
    private var lastFullSweepAt: Long? = null
    private val alreadyStored = LinkedHashSet<String>()
    private val rejected = LinkedHashSet<String>()

    // ── Cycle de vie ─────────────────────────────────────────────────────────

    override suspend fun start() {
        lock.withLock {
            if (started) return
            started = true
            _state.value = TransportState.RUNNING
            openSession()
        }
    }

    override suspend fun stop() {
        lock.withLock {
            if (!started) return
            started = false
            reconnectJob?.cancel(); reconnectJob = null
            closeSession()
            _state.value = TransportState.IDLE
        }
    }

    override suspend fun disconnect(peer: PeerHandle) {
        lock.withLock {
            if (peer != this.peer || session == null) return
            closeSession()
            scheduleReconnect()
        }
    }

    private fun openSession() {
        session = Session()
        _events.tryEmit(TransportEvent.PeerConnected(peer))
    }

    private fun closeSession() {
        val s = session ?: return
        session = null
        s.jobs.forEach(Job::cancel)
        _events.tryEmit(TransportEvent.PeerLost(peer))
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(config.reconnectDelay)
            lock.withLock { if (started && session == null) openSession() }
        }
    }

    // ── Envoi : ce que le moteur dit au « pair » ─────────────────────────────

    override suspend fun send(peer: PeerHandle, frame: ByteArray): SendResult {
        val s = lock.withLock { if (peer == this.peer) session else null } ?: return SendResult.PEER_GONE
        if (frame.size > capabilities.maxFrameBytes) return SendResult.FRAME_TOO_LARGE
        return when (val decoded = FrameCodec.decode(frame)) {
            null -> SendResult.FAILED
            is Frame.Inventory -> {
                val first = lock.withLock { !s.inventoryHandled.also { s.inventoryHandled = true } }
                if (!first) return SendResult.FAILED
                lock.withLock { s.jobs += scope.launch { serveInventory(s, decoded.filter) } }
                SendResult.SENT
            }
            is Frame.EnvelopeFrame -> publish(s, decoded.bytes)
        }
    }

    private suspend fun publish(s: Session, bytes: ByteArray): SendResult {
        val nowSeconds = clock() / 1000
        val env = (EnvelopeCodec.decode(bytes, nowSeconds) as? DecodeResult.Accepted)?.envelope ?: return SendResult.FAILED
        val doc = RelayDocument(env.id.toBytes(), env.destTag, env.createdAt + env.lifetime, bytes)
        if (doc.idHex in alreadyStored) return SendResult.SENT
        if (doc.idHex in rejected) return SendResult.FAILED
        if (s.published >= config.maxPublishPerSession) return SendResult.FAILED
        return when (backend.publish(doc)) {
            PublishResult.CREATED, PublishResult.EXISTS -> {
                s.published++
                remember(alreadyStored, doc.idHex)
                healthy()
                SendResult.SENT
            }
            PublishResult.REJECTED -> { remember(rejected, doc.idHex); SendResult.FAILED }
            PublishResult.FAILED -> { degraded(); SendResult.FAILED }
        }
    }

    // ── Réception : ce que le « pair » dit au moteur ─────────────────────────

    private suspend fun serveInventory(s: Session, engineFilter: BloomFilter) {
        val full = lastFullSweepAt?.let { clock() - it >= config.fullSweepInterval.inWholeMilliseconds } ?: true
        val docs = collect(full)
        if (docs == null) {
            // Relais injoignable : mieux vaut fermer le lien (le moteur ne gaspille rien) et réessayer plus tard.
            degraded()
            lock.withLock { if (session === s) { closeSession(); scheduleReconnect() } }
            return
        }
        if (full) lastFullSweepAt = clock()
        healthy()

        val ours = BloomFilter.create(maxOf(docs.size, 16), INVENTORY_FALSE_POSITIVE_RATE, random.nextBytes(BloomFilter.SALT_SIZE))
        docs.forEach { ours.add(it.id) }
        if (!deliver(s, FrameCodec.encodeInventory(ours))) return

        val missing = docs.filter { !engineFilter.mightContain(it.id) }
        pushPaced(s, missing)
        lock.withLock { if (session === s) s.jobs += scope.launch { pollLoop(s) } }
    }

    private suspend fun pollLoop(s: Session) {
        while (scope.isActive && lock.withLock { session === s }) {
            delay(config.pollInterval)
            val full = lastFullSweepAt?.let { clock() - it >= config.fullSweepInterval.inWholeMilliseconds } ?: true
            val docs = collect(full)
            if (docs == null) { degraded(); continue }
            if (full) lastFullSweepAt = clock()
            healthy()
            pushPaced(s, docs)
        }
    }

    /** Pousse au moteur ce qu'il n'a pas encore reçu pendant cette session, à un rythme que son seau à jetons accepte. */
    private suspend fun pushPaced(s: Session, docs: List<RelayDocument>) {
        var pushed = 0
        for (doc in docs) {
            if (lock.withLock { session !== s }) return
            if (!s.delivered.add(doc.idHex)) continue
            if (pushed >= config.pushBurst) delay(config.pushIntervalMillis)
            if (!deliver(s, FrameCodec.encodeEnvelope(doc.envelope))) return
            pushed++
        }
    }

    private suspend fun deliver(s: Session, frame: ByteArray): Boolean {
        if (lock.withLock { session !== s }) return false
        _events.tryEmit(TransportEvent.FrameReceived(peer, frame))
        return true
    }

    // ── Relève ───────────────────────────────────────────────────────────────

    /** Relève les enveloppes du relais pour les étiquettes écoutées ; `null` si tout a échoué. */
    private suspend fun collect(full: Boolean): List<RelayDocument>? {
        val nowSeconds = clock() / 1000
        val epoch = DestTags.epochOf(nowSeconds)
        val from = epoch - (if (full) config.maxAgeEpochs else config.recentEpochsBack)
        val tags = config.tags(from, epoch + 1)
        if (tags.isEmpty()) return emptyList()
        var failures = 0
        val found = LinkedHashMap<String, RelayDocument>()
        coroutineScope {
            tags.chunked(config.fetchParallelism).forEach { chunk ->
                chunk.map { tag -> async { backend.fetch(tag, nowSeconds, config.maxDocsPerTag) } }.awaitAll().forEach { result ->
                    val docs = result.docs
                    if (docs == null) failures++ else docs.forEach { found.putIfAbsent(it.idHex, it) }
                }
            }
        }
        if (failures == tags.size) return null
        if (failures > 0) degraded() // relève partielle : on garde ce qu'on a, l'état dit que ce n'est pas sain
        return found.values.toList()
    }

    // ── État ─────────────────────────────────────────────────────────────────

    private fun degraded() { if (started) _state.value = TransportState.DEGRADED }

    private fun healthy() { if (started && _state.value == TransportState.DEGRADED) _state.value = TransportState.RUNNING }

    private fun remember(set: LinkedHashSet<String>, id: String) {
        set.add(id)
        if (set.size > MEMORY) set.remove(set.first())
    }

    private companion object {
        const val INVENTORY_FALSE_POSITIVE_RATE = 0.001
        const val MEMORY = 4_096
    }
}
