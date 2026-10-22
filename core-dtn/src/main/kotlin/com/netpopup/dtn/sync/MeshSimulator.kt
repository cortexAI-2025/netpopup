package com.netpopup.dtn.sync

import com.netpopup.dtn.envelope.Envelope
import com.netpopup.dtn.envelope.EnvelopeId
import com.netpopup.dtn.store.EnvelopeStore
import com.netpopup.dtn.store.Origin
import kotlin.random.Random

/** Un appareil simulé : son store et les enveloppes qui lui ont été remises. */
class SimNode(val name: String, val store: EnvelopeStore = EnvelopeStore()) {
    /** Enveloppes acceptées depuis un pair, dans l'ordre d'arrivée (hors celles créées ici). */
    val received = mutableListOf<Envelope>()

    fun has(id: EnvelopeId): Boolean = store.contains(id)

    override fun toString() = "SimNode($name)"
}

data class MeetingResult(val sentByA: Int, val sentByB: Int, val statsA: SyncStats, val statsB: SyncStats)

/**
 * Simulateur multi-appareils sur la JVM, sans radio (architecture mesh v0.1, mesh-06).
 *
 * Les appareils se rencontrent par paires ([meet]) pendant une durée de contact ;
 * chaque rencontre fait tourner deux [SyncSession] reliées par un lien en mémoire.
 * Le temps est simulé : [nowSeconds] n'avance que par [advance] et par la durée
 * des contacts. Sert à valider le routage avant la radio : taux de livraison,
 * latence, profondeur des chemins.
 */
class MeshSimulator(
    startSeconds: Long = 1_776_000_000L,
    seed: Int = 1,
    private val burst: Int = SyncSession.DEFAULT_BURST,
    private val perSecond: Double = SyncSession.DEFAULT_PER_SECOND
) {
    val random = Random(seed)
    var nowSeconds: Long = startSeconds
        private set

    private val nodes = mutableListOf<SimNode>()

    fun node(name: String, store: EnvelopeStore = EnvelopeStore()): SimNode =
        SimNode(name, store).also(nodes::add)

    fun advance(seconds: Long) {
        require(seconds >= 0) { "seconds must be >= 0" }
        nowSeconds += seconds
    }

    /** Pose une enveloppe créée par [node] (origine `SELF`), sans aucun pair à portée. */
    fun create(node: SimNode, envelope: Envelope) {
        node.store.insert(envelope, Origin.SELF, nowSeconds)
    }

    /**
     * Deux appareils à portée l'un de l'autre pendant [contactSeconds] secondes.
     * Le contact consomme ce temps : l'horloge avance d'autant.
     */
    fun meet(a: SimNode, b: SimNode, contactSeconds: Int = 30): MeetingResult {
        require(contactSeconds >= 0) { "contactSeconds must be >= 0" }
        lateinit var sessionA: SyncSession
        lateinit var sessionB: SyncSession
        var clockMillis = nowSeconds * 1000

        fun session(self: SimNode, deliver: () -> SyncSession) = SyncSession(
            store = self.store,
            send = { frame -> deliver().onFrame(frame, clockMillis) },
            onEnvelopeAccepted = { self.received += it },
            saltProvider = { ByteArray(BloomFilter.SALT_SIZE).also(random::nextBytes) },
            burst = burst,
            perSecond = perSecond
        )
        sessionA = session(a) { sessionB }
        sessionB = session(b) { sessionA }

        sessionA.start()
        sessionB.start()
        var sentA = 0
        var sentB = 0
        for (second in 0..contactSeconds) {
            clockMillis = (nowSeconds + second) * 1000
            sentA += sessionA.pump(clockMillis)
            sentB += sessionB.pump(clockMillis)
        }
        nowSeconds += contactSeconds
        return MeetingResult(sentA, sentB, sessionA.stats, sessionB.stats)
    }

    /** Appareils qui stockent [id] en ce moment. */
    fun holders(id: EnvelopeId): List<SimNode> = nodes.filter { it.has(id) }

    /** Rencontres au hasard entre appareils distincts ; renvoie le nombre de rencontres jouées. */
    fun randomMeetings(count: Int, contactSeconds: Int = 30, gapSeconds: Long = 60): Int {
        require(nodes.size >= 2) { "need at least two nodes" }
        repeat(count) {
            val a = nodes[random.nextInt(nodes.size)]
            var b: SimNode
            do { b = nodes[random.nextInt(nodes.size)] } while (b === a)
            meet(a, b, contactSeconds)
            advance(gapSeconds)
        }
        return count
    }
}
