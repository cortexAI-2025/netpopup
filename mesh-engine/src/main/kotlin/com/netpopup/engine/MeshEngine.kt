package com.netpopup.engine

import com.netpopup.crypto.Contact
import com.netpopup.crypto.ContactBook
import com.netpopup.crypto.ContactBundle
import com.netpopup.crypto.ContactTrust
import com.netpopup.crypto.InvalidBundleException
import com.netpopup.crypto.LocalIdentity
import com.netpopup.crypto.OuterLayer
import com.netpopup.crypto.PairwiseSession
import com.netpopup.crypto.PreKeyManager
import com.netpopup.crypto.room.AdmissionPolicy as CryptoPolicy
import com.netpopup.crypto.room.RoomException
import com.netpopup.crypto.room.RoomNotReadyException
import com.netpopup.crypto.room.RoomInvite as CryptoInvite
import com.netpopup.crypto.room.RoomService
import com.netpopup.crypto.room.RoomState
import com.netpopup.dtn.chat.AdmissionPolicy
import com.netpopup.dtn.chat.ChatMessage
import com.netpopup.dtn.chat.Conversation
import com.netpopup.dtn.chat.ConversationId
import com.netpopup.dtn.chat.ConversationKind
import com.netpopup.dtn.chat.DeliveryState
import com.netpopup.dtn.chat.InvalidInviteException
import com.netpopup.dtn.chat.JoinRequest
import com.netpopup.dtn.chat.LOCAL_CONVERSATION_ID
import com.netpopup.dtn.chat.MessageId
import com.netpopup.dtn.chat.MessageRules
import com.netpopup.dtn.chat.MeshStatus
import com.netpopup.dtn.chat.PendingJoin
import com.netpopup.dtn.chat.RoomDetails
import com.netpopup.dtn.chat.RoomInvite
import com.netpopup.dtn.chat.RoomMember
import com.netpopup.dtn.chat.UnknownConversationException
import com.netpopup.dtn.direct.DirectEvent
import com.netpopup.dtn.direct.DirectMessenger
import com.netpopup.dtn.envelope.DecodeResult
import com.netpopup.dtn.envelope.DestTags
import com.netpopup.dtn.envelope.Envelope
import com.netpopup.dtn.envelope.EnvelopeCodec
import com.netpopup.dtn.envelope.EnvelopeId
import com.netpopup.dtn.envelope.EnvelopeKind
import com.netpopup.dtn.store.EnvelopeStore
import com.netpopup.dtn.store.Origin
import com.netpopup.dtn.sync.Frame
import com.netpopup.dtn.sync.FrameCodec
import com.netpopup.dtn.sync.SyncSession
import com.netpopup.dtn.transport.LinkEvent
import com.netpopup.dtn.transport.PeerHandle
import com.netpopup.dtn.transport.SendResult
import com.netpopup.dtn.transport.Transport
import com.netpopup.dtn.transport.TransportId
import com.netpopup.dtn.transport.TransportManager
import com.netpopup.dtn.transport.TransportManagerConfig
import com.netpopup.dtn.transport.TransportState
import com.netpopup.store.sql.Schema
import com.netpopup.store.sql.SqlConversationDao
import com.netpopup.store.sql.SqlDatabase
import com.netpopup.store.sql.SqlIdentityVault
import com.netpopup.store.sql.SqlMessageDao
import com.netpopup.store.sql.SqlPendingSendStore
import com.netpopup.store.sql.SqlSignalProtocolStore
import com.netpopup.store.sql.SqlStateStore
import com.netpopup.store.sql.SqlStoreBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.signal.libsignal.protocol.IdentityKey
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

class EngineConfig(
    /** Pseudo publié dans les annonces, les bundles de contact et les messages de room. */
    val displayName: () -> String = { "Me" },
    val maintenanceIntervalMillis: Long = 30_000,
    val purgeIntervalMillis: Long = 15 * 60_000L,
    val reannounceIntervalMillis: Long = 2 * 3_600_000L,
    val pumpIntervalMillis: Long = 250,
    val maxJoinRequestsPerRoom: Int = 50,
    /**
     * Un faux positif du filtre de Bloom cache une enveloppe au pair pour toute la session (un ACK arrive alors
     * un contact plus tard). À 0,1 % le filtre pèse ~13 Ko pour 5 000 enveloppes, sous la limite de trame Nearby.
     */
    val bloomFalsePositiveRate: Double = 0.001,
)

/**
 * Le moteur du mesh : il assemble le store persistant, la synchronisation par lien, les transports, les
 * sessions 1:1 avec acquittements, les rooms privées, la room publique et les introductions de contacts.
 *
 * **Fil unique** : le [scope] doit avoir un seul fil d'exécution (`Dispatchers.Default.limitedParallelism(1)`
 * en production, un dispatcher de test en tests). Tout l'état du moteur, y compris la base, n'est touché que
 * depuis ce fil ; les opérations publiques y sont donc rejouées.
 *
 * **Ce qui est vérifié** : un pair n'est compté « à portée » qu'après un `INVENTORY` bien formé ; un message
 * n'est « relayé » qu'après un envoi réussi sur un lien ; il n'est « acquitté » qu'à la réception d'un `ACK`
 * dont le jeton correspond à celui caché dans le message chiffré.
 */
class MeshEngine(
    private val db: SqlDatabase,
    private val transports: List<Transport>,
    parentScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: EngineConfig = EngineConfig(),
    transportConfig: TransportManagerConfig = TransportManagerConfig(),
    random: kotlin.random.Random = kotlin.random.Random.Default,
) {
    /**
     * Une erreur dans une opération ou un lien (lien d'invitation refusé, pair hostile) ne doit jamais faire
     * tomber le reste du moteur : un `SupervisorJob` isole les enfants.
     */
    private val scope: CoroutineScope =
        CoroutineScope(parentScope.coroutineContext + kotlinx.coroutines.SupervisorJob(parentScope.coroutineContext[Job]))

    init { Schema.migrate(db) }

    private val states = SqlStateStore(db)
    private val pendingSends = SqlPendingSendStore(db)
    private val conversationDao = SqlConversationDao(db)
    private val messageDao = SqlMessageDao(db)

    val identity: LocalIdentity = LocalIdentity.loadOrCreate(SqlIdentityVault(db))
    private val signal = SqlSignalProtocolStore(db, identity.keyPair, identity.registrationId)
    private val pairwise = PairwiseSession(signal)
    private val preKeys = PreKeyManager(signal, identity, true, clock)
    private val contacts = ContactBook { states.put(K_CONTACT, hex(it.identityKey.serialize()), encodeContact(it)) }
    private val rooms = RoomService(signal, identity, pairwise, contacts, clock)
    private val store = EnvelopeStore(SqlStoreBackend(db))
    private val manager = TransportManager(transports, scope, transportConfig, random, clock)

    private val roomStates = LinkedHashMap<ConversationId, RoomState>()
    private val messengers = HashMap<String, DirectMessenger>()
    private val links = HashMap<PeerHandle, Link>()
    private val offeredSelf = HashSet<EnvelopeId>()
    private val pendingJoins = LinkedHashMap<String, PendingJoinRec>()
    private val joinRequests = LinkedHashMap<String, JoinRequestRec>()
    private val directConversations = LinkedHashMap<ConversationId, IdentityKey>()

    private val conversationsFlow = MutableStateFlow<List<Conversation>>(emptyList())
    private val messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val pendingJoinsFlow = MutableStateFlow<List<PendingJoin>>(emptyList())
    private val joinRequestsFlow = MutableStateFlow<List<JoinRequest>>(emptyList())
    private val statusFlow = MutableStateFlow(MeshStatus.STOPPED)
    private val roomDetailsFlow = MutableStateFlow<Map<ConversationId, RoomDetails>>(emptyMap())

    private var started = false
    private var lastPurgeAt = 0L
    private var jobs = mutableListOf<Job>()

    /** Vue en lecture seule pour l'UI. */
    val meshStatus: StateFlow<MeshStatus> = statusFlow.asStateFlow()
    internal val conversations: StateFlow<List<Conversation>> = conversationsFlow.asStateFlow()
    internal val messages: StateFlow<List<ChatMessage>> = messagesFlow.asStateFlow()
    internal val pendingJoinList: StateFlow<List<PendingJoin>> = pendingJoinsFlow.asStateFlow()
    internal val joinRequestList: StateFlow<List<JoinRequest>> = joinRequestsFlow.asStateFlow()
    internal val roomDetails: StateFlow<Map<ConversationId, RoomDetails>> = roomDetailsFlow.asStateFlow()

    init {
        loadState()
        refreshAll()
    }

    // ── Cycle de vie ─────────────────────────────────────────────────────────

    /** Démarre les transports et les boucles du moteur ; renvoie les transports qui n'ont pas pu démarrer. */
    suspend fun start(): List<TransportId> = onEngine {
        if (started) return@onEngine emptyList()
        started = true
        jobs += scope.launch { manager.events.collect(::onLinkEvent) }
        transports.forEach { t -> jobs += scope.launch { t.state.collect { updateStatus() } } }
        val failed = manager.start()
        jobs += scope.launch { maintenanceLoop() }
        updateStatus()
        failed
    }

    suspend fun stop() = onEngine {
        if (!started) return@onEngine
        started = false
        jobs.forEach(Job::cancel); jobs.clear()
        manager.stop()
        links.values.forEach(Link::stop); links.clear()
        statusFlow.value = MeshStatus.STOPPED
    }

    /** Une passe d'entretien immédiate (purges, annonces, distribution des clés) ; utile aux tests. */
    suspend fun maintain() = onEngine { maintenanceTick() }

    private suspend fun <T> onEngine(block: suspend () -> T): T = scope.async { block() }.await()

    // ── Liens et synchronisation ─────────────────────────────────────────────

    private inner class Link(val peer: PeerHandle) {
        val outbox = Channel<ByteArray>(Channel.UNLIMITED)
        val session = SyncSession(
            store, send = { outbox.trySend(it) }, onEnvelopeAccepted = { onEnvelopeFromLink(this, it) },
            falsePositiveRate = config.bloomFalsePositiveRate,
        )
        var verified = false
        private var sender: Job? = null
        private var pump: Job? = null

        fun start() {
            sender = scope.launch {
                for (frame in outbox) {
                    when (manager.send(peer, frame)) {
                        SendResult.SENT -> onFrameSent(frame)
                        SendResult.PEER_GONE -> return@launch
                        else -> Unit
                    }
                }
            }
            pump = scope.launch {
                session.start()
                while (isActive) {
                    session.pump(clock())
                    delay(config.pumpIntervalMillis)
                }
            }
        }

        fun onFrame(frame: ByteArray) {
            session.onFrame(frame, clock())
            if (!verified && session.peerInventoryReceived) {
                verified = true
                updateStatus()
            }
        }

        fun stop() {
            sender?.cancel(); pump?.cancel(); outbox.close()
        }
    }

    private fun onLinkEvent(event: LinkEvent) {
        when (event) {
            is LinkEvent.LinkUp -> links.getOrPut(event.peer) { Link(event.peer).also(Link::start) }
            is LinkEvent.LinkDown -> links.remove(event.peer)?.stop()
            is LinkEvent.FrameReceived -> links[event.peer]?.onFrame(event.frame)
        }
        updateStatus()
        flushOffers()
    }

    private fun updateStatus() {
        val running = transports.any { it.state.value == TransportState.RUNNING || it.state.value == TransportState.DEGRADED }
        statusFlow.value = MeshStatus(isRunning = started && running, peersInRange = links.values.count { it.verified })
    }

    /** Une enveloppe reçue d'un lien : on la route vers les conversations et on la propose aux autres liens. */
    private fun onEnvelopeFromLink(from: Link, envelope: Envelope) {
        route(envelope)
        links.values.forEach { if (it !== from) it.session.offer(envelope) }
        flushOffers()
    }

    /** Propose à tous les liens les enveloppes que cet appareil vient de créer. */
    private fun flushOffers() {
        if (links.isEmpty()) return
        for (entry in store.all()) {
            if (entry.origin == Origin.SELF && offeredSelf.add(entry.id)) links.values.forEach { it.session.offer(entry.envelope) }
        }
    }

    /** Une trame d'enveloppe est partie sur un lien : les messages qu'elle porte passent à « relayé ». */
    private fun onFrameSent(frame: ByteArray) {
        val envelopeBytes = (FrameCodec.decode(frame) as? Frame.EnvelopeFrame)?.bytes ?: return
        val envelope = (EnvelopeCodec.decode(envelopeBytes, clock() / 1000) as? DecodeResult.Accepted)?.envelope ?: return
        markRelayed(envelope.id)
    }

    private fun markRelayed(id: EnvelopeId) {
        messengers.values.firstOrNull { it.owns(id) }?.markRelayed(id)?.let { setState(it, DeliveryState.RELAYED) }
        states.get(K_TX, id.hex)?.let { raw ->
            setState(MessageId(String(raw)), DeliveryState.RELAYED)
            states.delete(K_TX, id.hex)
        }
    }

    // ── Routage des enveloppes reçues ────────────────────────────────────────

    /** Aiguille une enveloppe nouvellement stockée vers la conversation à laquelle son étiquette correspond. */
    private fun route(envelope: Envelope) {
        if (handlePublic(envelope)) return
        if (handleInbox(envelope)) return
        if (handleDirect(envelope)) { flushOffers(); return }
        if (handleRooms(envelope)) { flushOffers(); return }
        handleReplies(envelope)
    }

    private fun handlePublic(env: Envelope): Boolean {
        if (env.kind != EnvelopeKind.DATA || !DestTags.matches(PublicRoom.keys.tagKey, env)) return false
        val message = PublicRoom.open(env.aad(), env.payload) ?: return true
        if (message.sender == identity.publicKey) return true
        addReceived(LOCAL_CONVERSATION_ID, env, message.sender, message.name, message.text)
        return true
    }

    private fun handleInbox(env: Envelope): Boolean {
        val keys = Inbox.keys(identity.publicKey)
        if (env.kind != EnvelopeKind.ANNOUNCE || !DestTags.matches(keys.tagKey, env)) return false
        val bytes = OuterLayer.open(keys.envKey, env.aad(), env.payload) ?: return true
        val bundle = try { ContactBundle.decode(bytes) } catch (e: InvalidBundleException) { return true }
        if (bundle.identityKey == identity.publicKey) return true
        if (contacts.find(bundle.identityKey) == null) {
            contacts.addFromAnnounce(bundle)
            ensureDirectConversation(bundle.identityKey, bundle.name)
            rescanDirect()
        }
        return true
    }

    private fun handleDirect(env: Envelope): Boolean {
        for (peer in knownPeers()) {
            val messenger = messenger(peer)
            if (!messenger.handles(env)) continue
            when (val event = messenger.handle(env)) {
                is DirectEvent.Received -> if (contacts.find(peer) != null) {
                    val conv = ensureDirectConversation(peer, contacts.find(peer)!!.name)
                    addReceived(conv, env, peer, contacts.find(peer)!!.name, event.body)
                }
                is DirectEvent.ControlReceived -> onControl(peer, event.payload)
                is DirectEvent.Acknowledged -> setState(event.messageId, DeliveryState.ACKNOWLEDGED)
                else -> Unit
            }
            return true
        }
        return false
    }

    private var rescanning = false

    /**
     * Relit les messages 1:1 déjà stockés : l'un d'eux a pu arriver avant l'introduction de son auteur (les
     * enveloppes ne voyagent pas dans l'ordre où elles ont été créées). À appeler quand un contact ou un
     * membre vient d'être appris.
     */
    private fun rescanDirect() {
        if (rescanning) return
        rescanning = true
        try {
            for (entry in store.all()) {
                val kind = entry.envelope.kind
                if (entry.origin == Origin.RELAY && (kind == EnvelopeKind.DATA || kind == EnvelopeKind.ACK)) handleDirect(entry.envelope)
            }
        } finally {
            rescanning = false
        }
    }

    private fun handleRooms(env: Envelope): Boolean {
        for ((convId, room) in roomStates.entries.toList()) {
            if (room.acceptedKeys(clock()).any { DestTags.matches(it.tagKey, env) }) {
                when (env.kind) {
                    EnvelopeKind.DATA -> openRoomMessage(convId, room, env)
                    EnvelopeKind.ANNOUNCE -> {
                        rooms.openAnnounce(room, env.aad(), env.payload)?.let { roomMaintenance(convId, room) }
                    }
                    else -> Unit
                }
                return true
            }
            if (env.kind == EnvelopeKind.DATA && DestTags.matches(room.joinKeys.tagKey, env)) {
                onJoinRequest(room, env)
                return true
            }
        }
        return false
    }

    private fun openRoomMessage(convId: ConversationId, room: RoomState, env: Envelope): Boolean {
        if (messageDao.exists(rxId(env))) return true
        val received = rooms.openMessage(room, env.aad(), env.payload) ?: return false
        val (name, text) = splitNamed(received.plaintext) ?: return true
        val shown = contacts.find(received.sender)?.name ?: name
        addReceived(convId, env, received.sender, shown, text)
        return true
    }

    /**
     * Relit ce qui est déjà stocké pour cette room : un message arrivé avant la Sender Key de son auteur, une
     * annonce arrivée avant que l'on soit membre ou que l'on connaisse le nouveau venu. Les enveloppes ne
     * voyagent pas dans l'ordre où elles ont été créées.
     */
    private fun rescanRoom(convId: ConversationId, room: RoomState) {
        val keys = room.acceptedKeys(clock())
        for (entry in store.all()) {
            val env = entry.envelope
            if (entry.origin != Origin.RELAY || (env.kind != EnvelopeKind.DATA && env.kind != EnvelopeKind.ANNOUNCE)) continue
            if (keys.none { DestTags.matches(it.tagKey, env) }) continue
            if (env.kind == EnvelopeKind.DATA) openRoomMessage(convId, room, env)
            else rooms.openAnnounce(room, env.aad(), env.payload)
        }
    }

    private fun handleReplies(env: Envelope) {
        if (env.kind != EnvelopeKind.DATA) return
        for (rec in pendingJoins.values.toList()) {
            val keys = rec.invite.replyKeys(identity.publicKey)
            if (!DestTags.matches(keys.tagKey, env)) continue
            val inner = OuterLayer.open(keys.envKey, env.aad(), env.payload) ?: return
            val decrypted = runCatching { pairwise.decrypt(inner, null) }.getOrNull()
                ?: contacts.all().firstNotNullOfOrNull { c -> runCatching { pairwise.decrypt(inner, c.identityKey) }.getOrNull() }
                ?: return
            val room = try {
                rooms.joinFromRoomKey(rec.invite, decrypted.sender, decrypted.plaintext)
            } catch (e: RoomException) {
                return
            }
            val convId = roomConversationId(room.roomId)
            roomStates[convId] = room
            persistRoom(convId, room)
            pendingJoins.remove(hex(rec.invite.roomId)); states.delete(K_PENDING_JOIN, hex(rec.invite.roomId))
            refreshAll()
            rescanRoom(convId, room)
            rescanDirect()
            roomMaintenance(convId, room)
            retryDeferred()
            return
        }
    }

    private fun onJoinRequest(room: RoomState, env: Envelope) {
        if (room.policy == CryptoPolicy.CREATOR_ONLY && room.creator != identity.publicKey) return
        val request = rooms.openJoin(room, env.aad(), env.payload) ?: return
        val requester = request.bundle.identityKey
        if (room.isMember(requester) || requester == identity.publicKey) return
        val id = hex(room.roomId) + ":" + hex(requester.serialize())
        if (id in joinRequests) return
        if (joinRequests.values.count { it.roomId.contentEquals(room.roomId) } >= config.maxJoinRequestsPerRoom) return
        val rec = JoinRequestRec(id, room.roomId, request.bundle, clock())
        joinRequests[id] = rec
        states.put(K_JOIN_REQUEST, id, rec.encode())
        refreshJoinRequests()
    }

    // ── Contrôle des rooms (ROOM_KEY, Sender Keys) reçu par session 1:1 ──────

    private enum class Applied { DONE, DROP, LATER }

    private fun onControl(peer: IdentityKey, payload: ByteArray) {
        // Le message est déjà acquitté et retiré du store : s'il ne peut pas encore s'appliquer, on le garde.
        when (applyControl(peer, payload)) {
            Applied.LATER -> deferControl(peer, payload)
            Applied.DONE -> retryDeferred()
            Applied.DROP -> Unit
        }
    }

    private fun applyControl(peer: IdentityKey, payload: ByteArray): Applied {
        if (payload.size < 17) return Applied.DROP
        val roomId = payload.copyOfRange(1, 17)
        val (convId, room) = roomStates.entries.firstOrNull { it.value.roomId.contentEquals(roomId) }?.toPair()
            ?: return Applied.LATER                         // notre propre ROOM_KEY n'est peut-être pas encore arrivé
        try {
            when (payload[0]) {
                RoomService.TYPE_ROOM_KEY -> rooms.onRoomKey(room, peer, payload)
                RoomService.TYPE_SENDER_KEY -> rooms.onSenderKey(room, peer, payload)
                else -> return Applied.DROP
            }
        } catch (e: RoomNotReadyException) {
            return Applied.LATER
        } catch (e: RoomException) {
            return Applied.DROP
        }
        persistRoom(convId, room)
        refreshConversations()
        rescanRoom(convId, room)
        rescanDirect()
        roomMaintenance(convId, room)
        return Applied.DONE
    }

    private var retrying = false

    private fun deferControl(peer: IdentityKey, payload: ByteArray) {
        val deferred = states.all(K_DEFER)
        if (deferred.size >= MAX_DEFERRED) states.delete(K_DEFER, deferred.minOf { it.first })
        states.put(K_DEFER, "%020d-%s".format(clock(), UUID.randomUUID().toString().take(8)), peer.serialize() + payload)
    }

    /** Rejoue les messages de contrôle reçus trop tôt, tant que l'un d'eux aboutit (l'ordre d'arrivée est libre). */
    private fun retryDeferred() {
        if (retrying) return
        retrying = true
        try {
            do {
                var progressed = false
                for ((id, data) in states.all(K_DEFER).sortedBy { it.first }) {
                    if (data.size < 34) { states.delete(K_DEFER, id); continue }
                    val peer = try { IdentityKey(data.copyOfRange(0, 33)) } catch (e: Exception) { states.delete(K_DEFER, id); continue }
                    when (applyControl(peer, data.copyOfRange(33, data.size))) {
                        Applied.DONE -> { states.delete(K_DEFER, id); progressed = true }
                        Applied.DROP -> states.delete(K_DEFER, id)
                        Applied.LATER -> Unit
                    }
                }
            } while (progressed)
        } finally {
            retrying = false
        }
    }

    // ── Entretien des rooms : annonces, Sender Keys, mises à jour de membres ──

    private fun roomMaintenance(convId: ConversationId, room: RoomState) {
        val roomHex = hex(room.roomId)
        // 1. Annonce : publie notre bundle pour que les membres puissent nous ouvrir une session 1:1.
        val announceAt = states.get(K_ANNOUNCE, roomHex)?.let { ByteBuffer.wrap(it).long } ?: 0L
        if (clock() - announceAt >= config.reannounceIntervalMillis) {
            val bundle = preKeys.currentBundle(config.displayName())
            createEnvelope(EnvelopeKind.ANNOUNCE, room.keys.tagKey, ANNOUNCE_LIFETIME) { aad -> rooms.sealAnnounce(room, aad, bundle) }
            states.put(K_ANNOUNCE, roomHex, ByteBuffer.allocate(8).putLong(clock()).array())
        }
        // 2. Sender Key de l'époque courante, une fois par membre joignable.
        val skDone: (IdentityKey) -> Boolean = { states.get(K_SK_SENT, skKey(roomHex, room.epoch, it)) != null }
        val distribution = rooms.senderKeyDistribution(room) { !skDone(it) }
        for (out in distribution.outgoing) {
            messenger(out.recipient).sendControl(out.payload, CONTROL_LIFETIME)
            states.put(K_SK_SENT, skKey(roomHex, room.epoch, out.recipient), byteArrayOf(1))
        }
        // 3. Liste de membres à jour pour ceux qui ne l'ont pas reçue (nouvel arrivant, nouvelle époque).
        val pending = states.all(K_MS_PENDING).filter { it.first.startsWith("$roomHex:") }
            .map { it.first.substringAfter(':') }.toSet()
        if (pending.isNotEmpty()) {
            val update = rooms.membershipUpdate(room, include = { hex(it.serialize()) in pending })
            for (out in update.outgoing) {
                messenger(out.recipient).sendControl(out.payload, CONTROL_LIFETIME)
                states.delete(K_MS_PENDING, "$roomHex:${hex(out.recipient.serialize())}")
            }
        }
        flushOffers()
    }

    private fun maintenanceTick() {
        for ((convId, room) in roomStates.entries.toList()) roomMaintenance(convId, room)
        val now = clock()
        if (now - lastPurgeAt >= config.purgeIntervalMillis) {
            lastPurgeAt = now
            store.purge(now / 1000)
            messageDao.purgeExpired(now)
            states.all(K_DEFER).filter { it.first.take(20).toLongOrNull()?.let { at -> now - at > DEFER_TTL_MILLIS } ?: true }
                .forEach { states.delete(K_DEFER, it.first) }
            pendingJoins.entries.removeAll { (now - it.value.requestedAt) > PENDING_JOIN_TTL_MILLIS }
                .also { if (it) refreshPendingJoins() }
            refreshMessages()
        }
        flushOffers()
    }

    private suspend fun maintenanceLoop() {
        while (scope.isActive) {
            maintenanceTick()
            delay(config.maintenanceIntervalMillis)
        }
    }

    // ── API publique ─────────────────────────────────────────────────────────

    suspend fun sendText(conversationId: ConversationId, body: String): ChatMessage = onEngine {
        val text = MessageRules.normalizeBody(body)
        val sentAt = clock()
        val name = config.displayName()
        val message: ChatMessage = when {
            conversationId == LOCAL_CONVERSATION_ID -> {
                val env = createEnvelope(EnvelopeKind.DATA, PublicRoom.keys.tagKey, DEFAULT_LIFETIME) { aad ->
                    PublicRoom.seal(identity, name, text, aad)
                }
                ownMessage(MessageId(UUID.randomUUID().toString()), conversationId, name, text, sentAt, env)
            }
            conversationId in directConversations -> {
                val peer = directConversations.getValue(conversationId)
                ensureSession(peer)
                val sent = messenger(peer).send(text)
                ownMessage(sent.messageId, conversationId, name, text, sentAt, null)
            }
            conversationId in roomStates -> {
                val room = roomStates.getValue(conversationId)
                val env = createEnvelope(EnvelopeKind.DATA, room.keys.tagKey, DEFAULT_LIFETIME) { aad ->
                    rooms.sealMessage(room, aad, named(name, text))
                }
                ownMessage(MessageId(UUID.randomUUID().toString()), conversationId, name, text, sentAt, env)
            }
            else -> throw UnknownConversationException(conversationId)
        }
        flushOffers()
        message
    }

    suspend fun markRead(conversationId: ConversationId) = onEngine {
        messageDao.markRead(conversationId, clock())
        messagesFlow.update { all -> all.map { if (it.conversationId == conversationId && !it.isOwn && it.readAt == null) it.copy(readAt = clock()) else it } }
    }

    suspend fun deleteLocally(messageId: MessageId) = onEngine {
        messageDao.delete(messageId)
        messagesFlow.update { all -> all.filterNot { it.id == messageId } }
    }

    suspend fun createRoom(title: String, admission: AdmissionPolicy): RoomInvite = onEngine {
        val policy = if (admission == AdmissionPolicy.CREATOR_ONLY) CryptoPolicy.CREATOR_ONLY else CryptoPolicy.ANY_MEMBER
        val (room, invite) = rooms.create(title.trim(), policy)
        val convId = roomConversationId(room.roomId)
        roomStates[convId] = room
        persistRoom(convId, room)
        refreshAll()
        roomMaintenance(convId, room)
        RoomInvite(convId, invite.toUri())
    }

    /** Dépose une demande d'accès sur la file de la room. Le lien est validé strictement. */
    suspend fun requestToJoin(inviteLink: String): PendingJoin = onEngine {
        val link = MessageRules.normalizeInviteLink(inviteLink)
        val invite = try { CryptoInvite.fromUri(link) } catch (e: RoomException) { throw InvalidInviteException("This invitation is damaged or not valid") }
        if (invite.creator == identity.publicKey && roomStates.values.any { it.roomId.contentEquals(invite.roomId) }) {
            throw InvalidInviteException("You created this room")
        }
        if (roomStates.values.any { it.roomId.contentEquals(invite.roomId) }) throw InvalidInviteException("You are already in this room")
        val rec = PendingJoinRec(invite, clock())
        pendingJoins[hex(invite.roomId)] = rec
        states.put(K_PENDING_JOIN, hex(invite.roomId), rec.encode())
        val bundle = preKeys.currentBundle(config.displayName())
        createEnvelope(EnvelopeKind.DATA, invite.joinKeys.tagKey, JOIN_LIFETIME) { aad -> rooms.sealJoin(invite, aad, bundle) }
        refreshPendingJoins()
        flushOffers()
        rec.toPending()
    }

    suspend fun approveJoinRequest(requestId: String) = onEngine {
        val rec = joinRequests[requestId] ?: return@onEngine
        val (convId, room) = roomStates.entries.firstOrNull { it.value.roomId.contentEquals(rec.roomId) }?.toPair() ?: return@onEngine
        val out = try {
            rooms.accept(room, RoomService.JoinRequest(rec.roomId, rec.bundle))
        } catch (e: RoomException) {
            return@onEngine
        }
        persistRoom(convId, room)
        val requester = rec.bundle.identityKey
        // Réponse sur la file dédiée au demandeur : il ne sait pas encore qui l'accepte.
        val reply = room.replyKeys(requester)
        val inner = pairwise.encrypt(requester, out.payload)
        createEnvelope(EnvelopeKind.DATA, reply.tagKey, JOIN_LIFETIME) { aad -> OuterLayer.seal(reply.envKey, aad, inner) }
        // Les autres membres doivent apprendre le nouvel arrivant.
        room.members.filter { it != identity.publicKey && it != requester }.forEach {
            states.put(K_MS_PENDING, "${hex(room.roomId)}:${hex(it.serialize())}", byteArrayOf(1))
        }
        joinRequests.remove(requestId); states.delete(K_JOIN_REQUEST, requestId)
        refreshAll()
        roomMaintenance(convId, room)
        retryDeferred()
    }

    suspend fun rejectJoinRequest(requestId: String) = onEngine {
        if (joinRequests.remove(requestId) != null) states.delete(K_JOIN_REQUEST, requestId)
        refreshJoinRequests()
    }

    /** Retire un membre (créateur seulement) : nouvelle époque, redistribuée aux membres restants. */
    suspend fun removeMember(conversationId: ConversationId, memberKey: String) = onEngine {
        val room = roomStates[conversationId] ?: throw UnknownConversationException(conversationId)
        val member = room.members.firstOrNull { senderKeyOf(it) == memberKey } ?: throw RoomException("not a member")
        val distribution = rooms.remove(room, member)
        persistRoom(conversationId, room)
        val sentTo = HashSet<String>()
        for (out in distribution.outgoing) {
            messenger(out.recipient).sendControl(out.payload, CONTROL_LIFETIME)
            sentTo += hex(out.recipient.serialize())
        }
        // Les membres sans session (bundle inconnu) recevront la nouvelle époque dès qu'on pourra leur écrire.
        room.members.filter { it != identity.publicKey && hex(it.serialize()) !in sentTo }.forEach {
            states.put(K_MS_PENDING, "${hex(room.roomId)}:${hex(it.serialize())}", byteArrayOf(1))
        }
        refreshAll()
        roomMaintenance(conversationId, room)
    }

    suspend fun addContact(contactLink: String): ConversationId = onEngine {
        val link = MessageRules.normalizeContactLink(contactLink)
        val bundle = try { ContactBundle.fromUri(link) } catch (e: InvalidBundleException) {
            throw InvalidInviteException("This contact link is damaged or its signatures are wrong")
        }
        if (bundle.identityKey == identity.publicKey) throw InvalidInviteException("This is your own contact link")
        val contact = contacts.addFromQr(bundle)
        ensureSession(contact.identityKey)
        val conv = ensureDirectConversation(contact.identityKey, bundle.name)
        // Se présenter : l'autre ne sait pas encore qui nous sommes.
        val inbox = Inbox.keys(contact.identityKey)
        val mine = preKeys.currentBundle(config.displayName())
        createEnvelope(EnvelopeKind.ANNOUNCE, inbox.tagKey, JOIN_LIFETIME) { aad -> OuterLayer.seal(inbox.envKey, aad, mine.encode()) }
        rescanDirect()
        flushOffers()
        conv
    }

    suspend fun myContactLink(): String = onEngine { preKeys.currentBundle(config.displayName()).toUri() }

    suspend fun leave(conversationId: ConversationId) = onEngine {
        if (conversationId == LOCAL_CONVERSATION_ID) return@onEngine
        roomStates.remove(conversationId)?.let { room ->
            val roomHex = hex(room.roomId)
            states.delete(K_ROOM, conversationId.value)
            states.deleteWhereIdStartsWith(K_SK_SENT, "$roomHex:")
            states.deleteWhereIdStartsWith(K_MS_PENDING, "$roomHex:")
            states.delete(K_ANNOUNCE, roomHex)
            joinRequests.entries.removeAll { it.value.roomId.contentEquals(room.roomId) }.also { states.deleteWhereIdStartsWith(K_JOIN_REQUEST, "$roomHex:") }
        }
        directConversations.remove(conversationId)
        conversationDao.delete(conversationId)
        messagesFlow.update { all -> all.filterNot { it.conversationId == conversationId } }
        refreshAll()
    }

    private fun detailsOf(room: RoomState): RoomDetails {
        val members = room.members.map {
            RoomMember(senderKeyOf(it), if (it == identity.publicKey) config.displayName() else contacts.find(it)?.name ?: "Member ${senderKeyOf(it).take(6)}", it == identity.publicKey)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        room.members.map { it.serialize() }.sortedWith { a, b -> java.util.Arrays.compareUnsigned(a, b) }.forEach(digest::update)
        val fingerprint = digest.digest().take(20).joinToString("") { "%02x".format(it) }.chunked(4).joinToString(" ")
        return RoomDetails(members, fingerprint, room.rotationInProgress)
    }

    // ── Aides ────────────────────────────────────────────────────────────────

    private fun createEnvelope(kind: EnvelopeKind, tagKey: ByteArray, lifetime: Int, seal: (ByteArray) -> ByteArray): Envelope {
        val createdAt = clock() / 1000
        val tag = DestTags.forSending(tagKey, createdAt)
        // L'AAD ne dépend pas du payload : on la calcule sur une enveloppe provisoire.
        val aad = Envelope(kind, tag, createdAt, lifetime, payload = ByteArray(Envelope.MIN_PAYLOAD_SIZE)).aad()
        val envelope = Envelope(kind, tag, createdAt, lifetime, payload = seal(aad))
        store.insert(envelope, Origin.SELF, createdAt)
        return envelope
    }

    private fun ownMessage(id: MessageId, conv: ConversationId, name: String, text: String, sentAt: Long, env: Envelope?): ChatMessage {
        val m = ChatMessage(id, conv, senderKeyOf(identity.publicKey), name, text, sentAt, sentAt + MessageRules.DEFAULT_LIFETIME_MS, true, DeliveryState.QUEUED)
        storeMessage(m)
        if (env != null) states.put(K_TX, env.id.hex, id.value.toByteArray())
        return m
    }

    private fun addReceived(conv: ConversationId, env: Envelope, sender: IdentityKey, name: String, text: String) {
        val id = rxId(env)
        if (messageDao.exists(id)) return
        val expiresAt = (env.createdAt + env.lifetime) * 1000
        if (expiresAt <= clock()) return
        storeMessage(ChatMessage(id, conv, senderKeyOf(sender), name, text, env.createdAt * 1000, expiresAt, false, DeliveryState.RECEIVED))
    }

    private fun storeMessage(m: ChatMessage) {
        messageDao.insert(m)
        messageDao.trim(m.conversationId, MessageRules.MAX_MESSAGES_PER_ROOM)
        refreshMessages()
    }

    private fun setState(id: MessageId, state: DeliveryState) {
        messageDao.updateState(id, state)
        messagesFlow.update { all -> all.map { if (it.id == id) it.copy(deliveryState = state) else it } }
    }

    private fun rxId(env: Envelope) = MessageId("rx-" + env.id.hex)

    private fun messenger(peer: IdentityKey): DirectMessenger =
        messengers.getOrPut(hex(peer.serialize())) { DirectMessenger(identity, peer, pairwise, store, clock, pendingSends) }

    private fun ensureSession(peer: IdentityKey) {
        if (pairwise.hasSession(peer)) return
        val bundle = contacts.find(peer)?.bundle ?: throw InvalidInviteException("No contact bundle for this peer")
        pairwise.start(bundle)
    }

    /** Contacts et membres de rooms (hors nous) : ceux dont l'étiquette 1:1 peut nous viser. */
    private fun knownPeers(): List<IdentityKey> =
        (contacts.all().map { it.identityKey } + roomStates.values.flatMap { it.members }).filter { it != identity.publicKey }.distinct()

    private fun ensureDirectConversation(peer: IdentityKey, name: String): ConversationId {
        val id = ConversationId("direct-" + senderKeyOf(peer))
        if (id !in directConversations) {
            directConversations[id] = peer
            conversationDao.upsert(Conversation(id, ConversationKind.DIRECT, name), clock())
            refreshConversations()
        }
        return id
    }

    private fun persistRoom(convId: ConversationId, room: RoomState) = states.put(K_ROOM, convId.value, room.encode())

    private fun roomConversationId(roomId: ByteArray) = ConversationId("room-" + hex(roomId))

    private fun senderKeyOf(ik: IdentityKey) = PairwiseSession.addressOf(ik).name

    private fun skKey(roomHex: String, epoch: Int, member: IdentityKey) = "$roomHex:$epoch:${hex(member.serialize())}"

    private fun named(name: String, text: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8).take(PublicRoom.MAX_NAME_BYTES).toByteArray()
        return byteArrayOf(n.size.toByte()) + n + text.toByteArray(Charsets.UTF_8)
    }

    private fun splitNamed(bytes: ByteArray): Pair<String, String>? {
        if (bytes.isEmpty()) return null
        val len = bytes[0].toInt() and 0xff
        if (len > PublicRoom.MAX_NAME_BYTES || bytes.size < 1 + len) return null
        return String(bytes, 1, len, Charsets.UTF_8) to String(bytes, 1 + len, bytes.size - 1 - len, Charsets.UTF_8)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ── Chargement et rafraîchissement des flux ──────────────────────────────

    private fun loadState() {
        states.all(K_CONTACT).forEach { (_, data) -> decodeContact(data)?.let(contacts::restore) }
        states.all(K_ROOM).forEach { (id, data) -> runCatching { RoomState.decode(data) }.getOrNull()?.let { roomStates[ConversationId(id)] = it } }
        states.all(K_PENDING_JOIN).forEach { (id, data) -> PendingJoinRec.decode(data)?.let { pendingJoins[id] = it } }
        states.all(K_JOIN_REQUEST).forEach { (id, data) -> JoinRequestRec.decode(id, data)?.let { joinRequests[id] = it } }
        for (c in conversationDao.all()) {
            if (c.kind != ConversationKind.DIRECT) continue
            contacts.all().firstOrNull { ConversationId("direct-" + senderKeyOf(it.identityKey)) == c.id }
                ?.let { directConversations[c.id] = it.identityKey }
        }
        // Les pré-clés sont créées dès le premier démarrage : le lien de contact est utilisable aussitôt.
        preKeys.rotateIfNeeded()
    }

    private fun refreshAll() {
        refreshConversations(); refreshMessages(); refreshPendingJoins(); refreshJoinRequests()
    }

    private fun refreshConversations() {
        val local = Conversation(LOCAL_CONVERSATION_ID, ConversationKind.LOCAL_PUBLIC, "Local")
        val directs = conversationDao.all().filter { it.kind == ConversationKind.DIRECT && it.id in directConversations }
        val roomList = roomStates.map { (id, room) ->
            Conversation(
                id, ConversationKind.ROOM, room.name,
                if (room.policy == CryptoPolicy.CREATOR_ONLY) AdmissionPolicy.CREATOR_ONLY else AdmissionPolicy.ANY_MEMBER,
                room.rotationInProgress,
            )
        }
        conversationsFlow.value = listOf(local) + directs + roomList
        roomDetailsFlow.value = roomStates.mapValues { detailsOf(it.value) }
    }

    private fun refreshMessages() {
        messagesFlow.value = messageDao.live(clock())
    }

    private fun refreshPendingJoins() {
        pendingJoinsFlow.value = pendingJoins.values.map { it.toPending() }
    }

    private fun refreshJoinRequests() {
        joinRequestsFlow.value = joinRequests.values.map { rec ->
            JoinRequest(rec.id, roomConversationId(rec.roomId), senderKeyOf(rec.bundle.identityKey), rec.bundle.name, rec.receivedAt)
        }
    }

    // ── Enregistrements ──────────────────────────────────────────────────────

    private class PendingJoinRec(val invite: CryptoInvite, val requestedAt: Long) {
        fun encode(): ByteArray {
            val inv = invite.encode()
            return ByteBuffer.allocate(2 + inv.size + 8).putShort(inv.size.toShort()).put(inv).putLong(requestedAt).array()
        }

        fun toPending() = PendingJoin(hexOf(invite.roomId).take(8), invite.name, requestedAt)

        companion object {
            fun decode(data: ByteArray): PendingJoinRec? = try {
                val b = ByteBuffer.wrap(data)
                val inv = ByteArray(b.short.toInt() and 0xffff).also { b.get(it) }
                PendingJoinRec(CryptoInvite.decode(inv), b.long)
            } catch (e: Exception) { null }
        }
    }

    private class JoinRequestRec(val id: String, val roomId: ByteArray, val bundle: ContactBundle, val receivedAt: Long) {
        fun encode(): ByteArray = ByteBuffer.allocate(24 + bundle.encode().size).put(roomId).putLong(receivedAt).put(bundle.encode()).array()

        companion object {
            fun decode(id: String, data: ByteArray): JoinRequestRec? = try {
                val b = ByteBuffer.wrap(data)
                val roomId = ByteArray(16).also { b.get(it) }
                val at = b.long
                JoinRequestRec(id, roomId, ContactBundle.decode(ByteArray(b.remaining()).also { b.get(it) }), at)
            } catch (e: Exception) { null }
        }
    }

    private fun encodeContact(c: Contact): ByteArray = byteArrayOf(c.trust.ordinal.toByte()) + c.bundle.encode()

    private fun decodeContact(data: ByteArray): Contact? = try {
        Contact(ContactBundle.decode(data.copyOfRange(1, data.size)), ContactTrust.entries[data[0].toInt()])
    } catch (e: Exception) { null }

    companion object {
        private const val K_CONTACT = "contact"
        private const val K_ROOM = "room"
        private const val K_PENDING_JOIN = "pending_join"
        private const val K_JOIN_REQUEST = "join_request"
        private const val K_SK_SENT = "sk_sent"
        private const val K_MS_PENDING = "ms_pending"
        private const val K_ANNOUNCE = "announce_at"
        private const val K_TX = "tx"
        private const val K_DEFER = "ctrl_deferred"
        private const val MAX_DEFERRED = 64

        const val DEFAULT_LIFETIME = Envelope.DEFAULT_LIFETIME_SECONDS
        const val ANNOUNCE_LIFETIME = Envelope.DEFAULT_LIFETIME_SECONDS
        const val CONTROL_LIFETIME = Envelope.MAX_LIFETIME_SECONDS
        const val JOIN_LIFETIME = Envelope.MAX_LIFETIME_SECONDS
        const val PENDING_JOIN_TTL_MILLIS = 24L * 3_600_000
        const val DEFER_TTL_MILLIS = 24L * 3_600_000

        private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
