package com.netpopup.dtn.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlin.random.Random

/**
 * [ChatRepository] en mémoire, pour les tests et les previews de l'UI.
 *
 * Il vit dans le code principal parce que l'app l'utilise **provisoirement** tant que l'implémentation
 * réelle (cœur DTN + crypto + transports) n'est pas écrite : les messages ne quittent pas l'appareil.
 *
 * Aucun réseau ni chiffrement : les méthodes `simulate*` jouent le rôle des
 * autres appareils. [now] permet de contrôler l'horloge (expiration).
 */
class InMemoryChatRepository(
    private val selfKey: String = "self",
    private val selfName: String = "Me",
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default
) : ChatRepository {

    private var nextId = 0L
    private fun newId(prefix: String) = "$prefix-${nextId++}"

    private val conversations = MutableStateFlow(
        listOf(Conversation(LOCAL_CONVERSATION_ID, ConversationKind.LOCAL_PUBLIC, "Local"))
    )
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val pendingJoins = MutableStateFlow<List<PendingJoin>>(emptyList())
    private val joinRequests = MutableStateFlow<List<JoinRequest>>(emptyList())
    private val roomCodes = mutableMapOf<String, ConversationId>()
    private val mesh = MutableStateFlow(MeshStatus.STOPPED)

    override val meshStatus: StateFlow<MeshStatus> = mesh.asStateFlow()

    override fun observeConversations(): Flow<List<Conversation>> = conversations

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMessages(conversationId: ConversationId): Flow<List<ChatMessage>> =
        messages.transformLatest { all ->
            // Ré-émet à chaque expiration, même si rien d'autre ne change.
            while (true) {
                val t = now()
                val live = all.filter { it.conversationId == conversationId && it.expiresAt > t }
                    .sortedBy { it.sentAt }
                emit(live)
                val nextExpiry = live.minOfOrNull { it.expiresAt } ?: break
                delay(nextExpiry - t)
            }
        }.distinctUntilChanged()

    override suspend fun sendText(conversationId: ConversationId, body: String): ChatMessage {
        requireConversation(conversationId)
        val sentAt = now()
        val message = ChatMessage(
            id = MessageId(newId("msg")),
            conversationId = conversationId,
            senderKey = selfKey,
            displayName = selfName,
            body = MessageRules.normalizeBody(body),
            sentAt = sentAt,
            expiresAt = sentAt + MessageRules.DEFAULT_LIFETIME_MS,
            isOwn = true,
            deliveryState = DeliveryState.QUEUED
        )
        append(message)
        return message
    }

    override suspend fun markRead(conversationId: ConversationId) {
        val t = now()
        messages.update { all ->
            all.map {
                if (it.conversationId == conversationId && !it.isOwn && it.readAt == null) it.copy(readAt = t) else it
            }
        }
    }

    override suspend fun deleteLocally(messageId: MessageId) {
        messages.update { all -> all.filterNot { it.id == messageId } }
    }

    override suspend fun createRoom(title: String, admission: AdmissionPolicy): RoomInvite {
        val id = ConversationId(newId("room"))
        val code = generateUniqueCode()
        roomCodes[code] = id
        conversations.update { it + Conversation(id, ConversationKind.ROOM, title, admission) }
        return RoomInvite(id, code)
    }

    override suspend fun requestToJoin(code: String): PendingJoin {
        val pending = PendingJoin(MessageRules.normalizeRoomCode(code), now())
        pendingJoins.update { list -> list.filterNot { it.code == pending.code } + pending }
        return pending
    }

    override fun observePendingJoins(): Flow<List<PendingJoin>> = pendingJoins

    override fun observeJoinRequests(): Flow<List<JoinRequest>> = joinRequests

    override suspend fun approveJoinRequest(requestId: String) {
        joinRequests.update { list -> list.filterNot { it.id == requestId } }
    }

    override suspend fun rejectJoinRequest(requestId: String) {
        joinRequests.update { list -> list.filterNot { it.id == requestId } }
    }

    override fun observeRoomDetails(conversationId: ConversationId): Flow<RoomDetails?> =
        conversations.map { list ->
            list.firstOrNull { it.id == conversationId && it.kind == ConversationKind.ROOM }?.let { room ->
                RoomDetails(
                    members = listOf(RoomMember(selfKey, selfName, isSelf = true)),
                    securityFingerprint = fingerprintOf(room.id),
                    keyRotationPending = room.keyRotationPending
                )
            }
        }.distinctUntilChanged()

    override suspend fun leave(conversationId: ConversationId) {
        if (conversationId == LOCAL_ID) return
        conversations.update { list -> list.filterNot { it.id == conversationId } }
        messages.update { all -> all.filterNot { it.conversationId == conversationId } }
        joinRequests.update { list -> list.filterNot { it.conversationId == conversationId } }
        roomCodes.values.remove(conversationId)
    }

    // ── Simulation des autres appareils ──────────────────────────────────────

    fun simulateIncomingMessage(
        conversationId: ConversationId,
        body: String,
        senderKey: String = "peer",
        displayName: String = "Peer",
        sentAt: Long = now()
    ): ChatMessage {
        requireConversation(conversationId)
        val message = ChatMessage(
            id = MessageId(newId("msg")),
            conversationId = conversationId,
            senderKey = senderKey,
            displayName = displayName,
            body = MessageRules.normalizeBody(body),
            sentAt = sentAt,
            expiresAt = sentAt + MessageRules.DEFAULT_LIFETIME_MS,
            isOwn = false,
            deliveryState = DeliveryState.RECEIVED
        )
        append(message)
        return message
    }

    /** Un membre accepte notre demande : la room apparaît, la demande disparaît. */
    fun simulateJoinAccepted(code: String, title: String): ConversationId {
        val normalized = MessageRules.normalizeRoomCode(code)
        val id = ConversationId(newId("room"))
        pendingJoins.update { list -> list.filterNot { it.code == normalized } }
        conversations.update { it + Conversation(id, ConversationKind.ROOM, title, AdmissionPolicy.ANY_MEMBER) }
        return id
    }

    fun simulateJoinRequest(conversationId: ConversationId, displayName: String = "Peer"): JoinRequest {
        requireConversation(conversationId)
        val request = JoinRequest(newId("join"), conversationId, "peer-${nextId}", displayName, now())
        joinRequests.update { it + request }
        return request
    }

    fun simulateDeliveryState(messageId: MessageId, state: DeliveryState) {
        messages.update { all -> all.map { if (it.id == messageId) it.copy(deliveryState = state) else it } }
    }

    fun setMeshStatus(status: MeshStatus) {
        mesh.value = status
    }

    // ── Interne ──────────────────────────────────────────────────────────────

    /** Empreinte factice mais stable, pour que les previews et les tests aient quelque chose à afficher. */
    private fun fingerprintOf(id: ConversationId): String =
        id.value.hashCode().toLong().and(0xFFFFFFFFL).toString().padStart(10, '0').chunked(5).joinToString(" ")

    private fun requireConversation(id: ConversationId) {
        if (conversations.value.none { it.id == id }) throw UnknownConversationException(id)
    }

    /** Ajoute en respectant le plafond par room (les plus anciens partent). */
    private fun append(message: ChatMessage) {
        messages.update { all ->
            val sameRoom = all.filter { it.conversationId == message.conversationId } + message
            val overflow = (sameRoom.size - MessageRules.MAX_MESSAGES_PER_ROOM).coerceAtLeast(0)
            val dropped = sameRoom.sortedBy { it.sentAt }.take(overflow).map { it.id }.toSet()
            (all + message).filterNot { it.id in dropped }
        }
    }

    private fun generateUniqueCode(): String {
        while (true) {
            val code = (1..MessageRules.ROOM_CODE_LENGTH)
                .map { MessageRules.ROOM_CODE_ALPHABET[random.nextInt(MessageRules.ROOM_CODE_ALPHABET.length)] }
                .joinToString("")
            if (code !in roomCodes) return code
        }
    }

    companion object {
        val LOCAL_ID = LOCAL_CONVERSATION_ID
    }
}
