package com.netpopup.engine

import com.netpopup.dtn.chat.AdmissionPolicy
import com.netpopup.dtn.chat.ChatMessage
import com.netpopup.dtn.chat.ChatRepository
import com.netpopup.dtn.chat.Conversation
import com.netpopup.dtn.chat.ConversationId
import com.netpopup.dtn.chat.JoinRequest
import com.netpopup.dtn.chat.MeshStatus
import com.netpopup.dtn.chat.MessageId
import com.netpopup.dtn.chat.PendingJoin
import com.netpopup.dtn.chat.RoomDetails
import com.netpopup.dtn.chat.RoomInvite
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/**
 * Implémentation de production de [ChatRepository] : tout passe par le [MeshEngine], qui porte le store
 * persistant, la crypto, les rooms, les acquittements, la synchronisation et les transports.
 */
class MeshChatRepository(
    private val engine: MeshEngine,
    private val clock: () -> Long = System::currentTimeMillis,
) : ChatRepository {

    override val meshStatus: Flow<MeshStatus> = engine.meshStatus

    override fun observeConversations(): Flow<List<Conversation>> = engine.conversations

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMessages(conversationId: ConversationId): Flow<List<ChatMessage>> =
        engine.messages.transformLatest { all ->
            // Ré-émet à chaque expiration, même si rien d'autre ne change.
            while (true) {
                val t = clock()
                val live = all.filter { it.conversationId == conversationId && it.expiresAt > t }
                emit(live)
                val next = live.minOfOrNull { it.expiresAt } ?: break
                delay(next - t)
            }
        }.distinctUntilChanged()

    override suspend fun sendText(conversationId: ConversationId, body: String): ChatMessage =
        engine.sendText(conversationId, body)

    override suspend fun markRead(conversationId: ConversationId) = engine.markRead(conversationId)

    override suspend fun deleteLocally(messageId: MessageId) = engine.deleteLocally(messageId)

    override suspend fun createRoom(title: String, admission: AdmissionPolicy): RoomInvite =
        engine.createRoom(title, admission)

    override suspend fun requestToJoin(inviteLink: String): PendingJoin = engine.requestToJoin(inviteLink)

    override suspend fun addContact(contactLink: String): ConversationId = engine.addContact(contactLink)

    override suspend fun myContactLink(): String = engine.myContactLink()

    override fun observePendingJoins(): Flow<List<PendingJoin>> = engine.pendingJoinList

    override fun observeJoinRequests(): Flow<List<JoinRequest>> = engine.joinRequestList

    override suspend fun approveJoinRequest(requestId: String) = engine.approveJoinRequest(requestId)

    override suspend fun rejectJoinRequest(requestId: String) = engine.rejectJoinRequest(requestId)

    override fun observeRoomDetails(conversationId: ConversationId): Flow<RoomDetails?> =
        engine.roomDetails.map { it[conversationId] }.distinctUntilChanged()

    override suspend fun leave(conversationId: ConversationId) = engine.leave(conversationId)
}
