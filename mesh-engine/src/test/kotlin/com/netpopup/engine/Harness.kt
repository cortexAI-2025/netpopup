package com.netpopup.engine

import com.netpopup.dtn.chat.ChatMessage
import com.netpopup.dtn.chat.Conversation
import com.netpopup.dtn.chat.ConversationId
import com.netpopup.dtn.chat.ConversationKind
import com.netpopup.dtn.chat.DeliveryState
import com.netpopup.dtn.transport.CostClass
import com.netpopup.dtn.transport.PeerHandle
import com.netpopup.dtn.transport.SendResult
import com.netpopup.dtn.transport.Transport
import com.netpopup.dtn.transport.TransportCapabilities
import com.netpopup.dtn.transport.TransportEvent
import com.netpopup.dtn.transport.TransportId
import com.netpopup.dtn.transport.TransportState
import com.netpopup.dtn.transport.TransportManagerConfig
import com.netpopup.store.sql.JdbcSqlDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import java.io.File
import kotlin.time.Duration.Companion.hours

/** Radio simulée : des transports qui ne se voient que si on les « rapproche ». */
class LoopbackNetwork(
    private val scope: CoroutineScope? = null,
    /** Délai avant que deux appareils tournés (déconnectés par le gestionnaire) se redécouvrent, comme avec Nearby. */
    private val rediscoverMillis: Long = 75_000,
) {
    private val transports = HashMap<String, LoopbackTransport>()
    private val together = HashSet<Set<String>>()
    private val reconnects = HashMap<Set<String>, kotlinx.coroutines.Job>()
    val log = ArrayList<String>()
    var clockForLog: () -> Long = { 0L }

    fun register(t: LoopbackTransport) { transports[t.name] = t }

    fun near(a: String, b: String): Boolean = setOf(a, b) in together

    /** Rapproche deux appareils : chacun voit apparaître l'autre. */
    fun connect(a: String, b: String) {
        reconnects.remove(setOf(a, b))?.cancel()
        if (!together.add(setOf(a, b))) { log += "t=${clockForLog() / 1000} connect($a,$b) IGNORED already together"; return }
        log += "t=${clockForLog() / 1000} connect($a,$b)"
        transports[a]?.emit(TransportEvent.PeerConnected(PeerHandle(TransportId.NEARBY, b)))
        transports[b]?.emit(TransportEvent.PeerConnected(PeerHandle(TransportId.NEARBY, a)))
    }

    /** Un côté a fermé le lien (rotation) : ils s'éloignent un instant puis se redécouvrent. */
    fun rotate(a: String, b: String) {
        if (setOf(a, b) !in together) { log += "t=${clockForLog() / 1000} rotate($a,$b) IGNORED not together"; return }
        log += "t=${clockForLog() / 1000} rotate($a,$b)"
        disconnect(a, b)
        val key = setOf(a, b)
        reconnects[key] = scope?.launch { delay(rediscoverMillis); reconnects.remove(key); connect(a, b) } ?: return
    }

    fun disconnect(a: String, b: String) {
        reconnects.remove(setOf(a, b))?.cancel()
        if (!together.remove(setOf(a, b))) return
        transports[a]?.emit(TransportEvent.PeerLost(PeerHandle(TransportId.NEARBY, b)))
        transports[b]?.emit(TransportEvent.PeerLost(PeerHandle(TransportId.NEARBY, a)))
    }

    /** Un appareil qui redémarre perd ses liens radio (sans que l'autre côté soit prévenu avant sa redécouverte). */
    fun dropAll(name: String) {
        for (pair in together.filter { name in it }) {
            val other = (pair - name).single()
            disconnect(name, other)               // l'autre côté est prévenu, comme avec une vraie radio
        }
        reconnects.keys.filter { name in it }.forEach { reconnects.remove(it)?.cancel() }
    }

    fun deliver(from: String, to: String, frame: ByteArray): SendResult {
        if (!near(from, to)) return SendResult.PEER_GONE
        val target = transports[to] ?: return SendResult.PEER_GONE
        target.emit(TransportEvent.FrameReceived(PeerHandle(TransportId.NEARBY, from), frame))
        return SendResult.SENT
    }
}

class LoopbackTransport(val name: String, private val network: LoopbackNetwork) : Transport {
    override val id = TransportId.NEARBY
    override val capabilities = TransportCapabilities(32_768, 4, false, CostClass.FREE)
    private val _state = MutableStateFlow(TransportState.IDLE)
    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 4096)
    override val state: StateFlow<TransportState> = _state
    override val events: Flow<TransportEvent> = _events.asSharedFlow()
    val sentFrames = ArrayList<ByteArray>()

    init { network.register(this) }

    fun emit(e: TransportEvent) { _events.tryEmit(e) }
    override suspend fun start() { _state.value = TransportState.RUNNING }
    override suspend fun stop() { _state.value = TransportState.IDLE }
    override suspend fun send(peer: PeerHandle, frame: ByteArray): SendResult {
        sentFrames += frame
        return network.deliver(name, peer.value, frame)
    }
    override suspend fun disconnect(peer: PeerHandle) = network.rotate(name, peer.value)
}

/** Un téléphone : base SQLite sur disque, moteur réel, transport simulé. Peut être redémarré. */
class TestNode(
    val name: String,
    private val network: LoopbackNetwork,
    private val scope: CoroutineScope,
    private val dir: File,
    private val clock: () -> Long,
    private val transportConfig: TransportManagerConfig = TransportManagerConfig(),
    private val seed: Int = 7,
    private val lifetimeSeconds: Int? = null,
) {
    private val file = File(dir, "$name.db")
    private lateinit var db: JdbcSqlDatabase
    lateinit var engine: MeshEngine
    lateinit var repo: MeshChatRepository
    lateinit var transport: LoopbackTransport

    init { boot() }

    private fun boot() {
        db = JdbcSqlDatabase.open(file)
        transport = LoopbackTransport(name, network)
        engine = MeshEngine(
            db, listOf(transport), scope, clock,
            // Taux de faux positifs quasi nul : un faux positif (0,1 % en production, rattrapé au contact suivant) rend
            // un test non déterministe sans rien prouver de plus.
            EngineConfig(
                displayName = { name }, pumpIntervalMillis = 100, maintenanceIntervalMillis = 10_000, bloomFalsePositiveRate = 1e-6,
                messageLifetimeSeconds = { lifetimeSeconds ?: com.netpopup.dtn.envelope.Envelope.DEFAULT_LIFETIME_SECONDS },
            ),
            transportConfig, kotlin.random.Random(seed),
        )
        repo = MeshChatRepository(engine, clock)
    }

    suspend fun start() { engine.start() }

    /** Coupure de courant : le moteur s'arrête, la base reste ; le téléphone redémarre sur les mêmes fichiers. */
    suspend fun restart(): TestNode {
        engine.stop()
        db.close()
        network.dropAll(name)
        boot()
        engine.start()
        return this
    }

    suspend fun stop() { engine.stop(); db.close() }

    suspend fun conversations(): List<Conversation> = repo.observeConversations().first()
    suspend fun messages(id: ConversationId): List<ChatMessage> = repo.observeMessages(id).first()

    /** La conversation 1:1 avec [other], si elle existe. */
    suspend fun directWith(other: TestNode): ConversationId? {
        val key = com.netpopup.crypto.PairwiseSession.addressOf(other.engine.identity.publicKey).name
        return conversations().firstOrNull { it.kind == ConversationKind.DIRECT && it.id.value == "direct-$key" }?.id
    }

    suspend fun roomNamed(title: String): Conversation? = conversations().firstOrNull { it.kind == ConversationKind.ROOM && it.title == title }

    suspend fun state(id: ConversationId, body: String): DeliveryState? = messages(id).firstOrNull { it.body == body }?.deliveryState

    suspend fun bodies(id: ConversationId): List<String> = messages(id).map { it.body }

    /** Les textes échangés avec [other], ou rien tant que la conversation n'existe pas. */
    suspend fun bodiesWith(other: TestNode): List<String> = directWith(other)?.let { bodies(it) } ?: emptyList()
}

/** Avance le temps virtuel : les boucles de synchronisation et d'entretien tournent pendant ce temps. */
suspend fun settle(seconds: Int = 10) = delay(seconds * 1000L)

suspend fun settleHours(h: Int) = delay(h.hours)

/** Un monde simulé : une radio, des téléphones, un temps virtuel qui sert d'horloge à tous. */
class World(private val testScope: TestScope, private val dir: File, private val base: Long = 1_700_000_000_000L) {
    val network = LoopbackNetwork(testScope.backgroundScope).also { n -> n.clockForLog = { testScope.currentTime } }

    /** [skewMillis] : l'horloge de ce téléphone avance (ou retarde) de cette durée sur celle du monde. */
    fun node(name: String, skewMillis: Long = 0, lifetimeSeconds: Int? = null): TestNode =
        TestNode(name, network, testScope.backgroundScope, dir, { base + skewMillis + testScope.currentTime }, lifetimeSeconds = lifetimeSeconds)

    /** Crée et démarre plusieurs téléphones. */
    suspend fun nodes(vararg names: String): List<TestNode> = names.map { node(it).also { n -> n.start() } }
}

/**
 * Attend, en temps virtuel, que [actual] vaille [expected], puis l'affirme. Les liens tournent toutes les 5 à 10
 * minutes et mettent jusqu'à ~75 s à se rétablir : une attente fixe serait fragile, une condition ne l'est pas.
 */
suspend fun <T> awaitEquals(expected: T, message: String = "", seconds: Int = 1800, actual: suspend () -> T) {
    var waited = 0
    while (actual() != expected && waited < seconds) { settle(5); waited += 5 }
    org.junit.Assert.assertEquals(message, expected, actual())
}
