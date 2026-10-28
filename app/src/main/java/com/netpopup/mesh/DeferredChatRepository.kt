package com.netpopup.mesh

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** [ChatRepository] dont le moteur réel n'est prêt qu'après ouverture de la base : tout attend the lazy delegate. */
class DeferredChatRepository(private val delegate: suspend () -> ChatRepository) : ChatRepository {

    private fun <T> flowOf(block: (ChatRepository) -> Flow<T>): Flow<T> = flow { emitAll(block(delegate())) }

    override val meshStatus: Flow<MeshStatus> = flowOf { it.meshStatus }

    override fun observeConversations(): Flow<List<Conversation>> = flowOf { it.observeConversations() }

    override fun observeMessages(conversationId: ConversationId): Flow<List<ChatMessage>> =
        flowOf { it.observeMessages(conversationId) }

    override suspend fun sendText(conversationId: ConversationId, body: String): ChatMessage =
        delegate().sendText(conversationId, body)

    override suspend fun markRead(conversationId: ConversationId) = delegate().markRead(conversationId)

    override suspend fun deleteLocally(messageId: MessageId) = delegate().deleteLocally(messageId)

    override suspend fun createRoom(title: String, admission: AdmissionPolicy): RoomInvite =
        delegate().createRoom(title, admission)

    override suspend fun requestToJoin(inviteLink: String): PendingJoin = delegate().requestToJoin(inviteLink)

    override suspend fun addContact(contactLink: String): ConversationId = delegate().addContact(contactLink)

    override suspend fun myContactLink(): String = delegate().myContactLink()

    override fun observePendingJoins(): Flow<List<PendingJoin>> = flowOf { it.observePendingJoins() }

    override fun observeJoinRequests(): Flow<List<JoinRequest>> = flowOf { it.observeJoinRequests() }

    override suspend fun approveJoinRequest(requestId: String) = delegate().approveJoinRequest(requestId)

    override suspend fun rejectJoinRequest(requestId: String) = delegate().rejectJoinRequest(requestId)

    override fun observeRoomDetails(conversationId: ConversationId): Flow<RoomDetails?> =
        flowOf { it.observeRoomDetails(conversationId) }

    override suspend fun leave(conversationId: ConversationId) = delegate().leave(conversationId)

    override fun observeBlocked(): Flow<Set<String>> = flowOf { it.observeBlocked() }

    override suspend fun block(senderKey: String) = delegate().block(senderKey)

    override suspend fun unblock(senderKey: String) = delegate().unblock(senderKey)

    override fun observeIncoming(): Flow<ChatMessage> = flowOf { it.observeIncoming() }

    override fun observeMeshInfo(): Flow<com.netpopup.dtn.chat.MeshInfo> = flowOf { it.observeMeshInfo() }

    override suspend fun myFingerprint(): String = delegate().myFingerprint()
}
