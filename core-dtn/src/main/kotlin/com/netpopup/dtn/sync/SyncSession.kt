package com.netpopup.dtn.sync

import com.netpopup.dtn.envelope.DecodeResult
import com.netpopup.dtn.envelope.Envelope
import com.netpopup.dtn.envelope.EnvelopeCodec
import com.netpopup.dtn.envelope.EnvelopeKind
import com.netpopup.dtn.envelope.toBytes
import com.netpopup.dtn.store.EnvelopeStore
import com.netpopup.dtn.store.InsertResult
import com.netpopup.dtn.store.Origin
import com.netpopup.dtn.store.StoredEnvelope
import java.security.SecureRandom

data class SyncStats(
    val sent: Int = 0,
    val accepted: Int = 0,
    /** Trames refusées : mal formées, enveloppe invalide, déjà connue, expirée ou store plein. */
    val rejected: Int = 0,
    /** Trames `ENVELOPE` écartées parce que le pair dépasse son débit autorisé. */
    val throttled: Int = 0,
    val malformed: Int = 0
)

/**
 * Une session d'anti-entropie avec un pair, pour la durée d'une connexion
 * (architecture mesh v0.1, section 4).
 *
 * 1. [start] envoie notre `INVENTORY` : un filtre de Bloom de tous les IDs que nous
 *    connaissons (stockés ou sous pierre tombale), salé à chaque session.
 * 2. À la réception de l'`INVENTORY` du pair, on file d'attente les enveloppes qu'il
 *    n'a pas, dans l'ordre : `ACK`, puis `SELF`, puis les plus récentes.
 * 3. [pump] envoie cette file à raison d'une rafale de [burst] enveloppes, puis
 *    [perSecond] par seconde. Chaque enveloppe part avec `hopCount` + 1.
 * 4. Les trames `ENVELOPE` reçues sont validées par [EnvelopeCodec] puis stockées
 *    comme `RELAY` ; un `ACK` accepté supprime l'enveloppe `DATA` correspondante.
 *
 * Écart par rapport au document : pas de trame `REQUEST`. Avec le filtre de Bloom,
 * l'émetteur déduit lui-même ce qui manque au pair ; un faux positif (rare, 1 %)
 * est rattrapé par le sel différent à la session suivante. Le chiffrement du lien
 * (`HELLO`, X25519 éphémère) dépend de `:core-crypto` et arrive avec mesh-09 (#10).
 *
 * Un `INVENTORY` ne compte qu'une fois par session : un pair ne peut pas relancer
 * le calcul de la file en boucle. Les méthodes ne sont pas thread-safe : un thread
 * par session.
 */
class SyncSession(
    private val store: EnvelopeStore,
    private val send: (ByteArray) -> Unit,
    private val onEnvelopeAccepted: (Envelope) -> Unit = {},
    private val saltProvider: () -> ByteArray = { ByteArray(BloomFilter.SALT_SIZE).also(SecureRandom()::nextBytes) },
    burst: Int = DEFAULT_BURST,
    perSecond: Double = DEFAULT_PER_SECOND,
    private val falsePositiveRate: Double = DEFAULT_FALSE_POSITIVE_RATE
) {
    private val outgoing = TokenBucket(burst, perSecond)
    private val incoming = TokenBucket(burst, perSecond)
    private val queue = ArrayDeque<StoredEnvelope>()
    private var started = false
    /** Vrai une fois un `INVENTORY` bien formé reçu : preuve que le pair parle notre protocole. */
    var peerInventoryReceived = false
        private set

    var stats = SyncStats()
        private set

    /** Nombre d'enveloppes encore en attente d'envoi. */
    val pending: Int get() = queue.size

    fun start() {
        check(!started) { "session already started" }
        started = true
        val ids = store.knownIds()
        val filter = BloomFilter.create(ids.size, falsePositiveRate, saltProvider())
        ids.forEach { filter.add(it.toBytes()) }
        send(FrameCodec.encodeInventory(filter))
    }

    fun onFrame(frame: ByteArray, nowMillis: Long) {
        when (val decoded = FrameCodec.decode(frame)) {
            null -> stats = stats.copy(malformed = stats.malformed + 1)
            is Frame.Inventory -> onInventory(decoded.filter, nowMillis / 1000)
            is Frame.EnvelopeFrame -> onEnvelope(decoded.bytes, nowMillis)
        }
    }

    /** Envoie ce que le débit autorisé permet ; renvoie le nombre d'enveloppes parties. */
    fun pump(nowMillis: Long): Int {
        val nowSeconds = nowMillis / 1000
        var sentNow = 0
        while (queue.isNotEmpty()) {
            val entry = queue.first()
            // Entre-temps, l'enveloppe a pu expirer ou être acquittée
            val current = store.get(entry.id)
            if (current == null || current.envelope.isExpired(nowSeconds)) {
                queue.removeFirst()
                continue
            }
            if (!outgoing.tryTake(nowMillis)) break
            queue.removeFirst()
            send(FrameCodec.encodeEnvelope(EnvelopeCodec.encode(current.envelope.forRelay())))
            sentNow++
        }
        stats = stats.copy(sent = stats.sent + sentNow)
        return sentNow
    }

    private fun onInventory(peerFilter: BloomFilter, nowSeconds: Long) {
        if (peerInventoryReceived) {
            stats = stats.copy(rejected = stats.rejected + 1)
            return
        }
        peerInventoryReceived = true
        store.all()
            .filter { !it.envelope.isExpired(nowSeconds) && !peerFilter.mightContain(it.id.toBytes()) }
            .sortedWith(
                compareBy<StoredEnvelope> { it.envelope.kind != EnvelopeKind.ACK }
                    .thenBy { it.origin != Origin.SELF }
                    .thenByDescending { it.envelope.createdAt }
            )
            .forEach(queue::addLast)
    }

    private fun onEnvelope(bytes: ByteArray, nowMillis: Long) {
        if (!incoming.tryTake(nowMillis)) {
            stats = stats.copy(throttled = stats.throttled + 1)
            return
        }
        val nowSeconds = nowMillis / 1000
        val envelope = (EnvelopeCodec.decode(bytes, nowSeconds) as? DecodeResult.Accepted)?.envelope
        if (envelope == null) {
            stats = stats.copy(rejected = stats.rejected + 1)
            return
        }
        when (store.insert(envelope, Origin.RELAY, nowSeconds)) {
            is InsertResult.Stored -> {
                stats = stats.copy(accepted = stats.accepted + 1)
                if (envelope.kind == EnvelopeKind.ACK) store.applyAck(envelope.ack)
                onEnvelopeAccepted(envelope)
            }
            else -> stats = stats.copy(rejected = stats.rejected + 1)
        }
    }

    companion object {
        /** Rafale initiale par pair (architecture v0.1, section 3). */
        const val DEFAULT_BURST = 20

        /** Débit ensuite, en enveloppes par seconde. */
        const val DEFAULT_PER_SECOND = 1.0

        const val DEFAULT_FALSE_POSITIVE_RATE = 0.01
    }
}
