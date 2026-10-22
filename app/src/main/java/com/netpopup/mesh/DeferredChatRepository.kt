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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** [ChatRepository] dont le moteur réel n'est prêt qu'après ouverture de la base : tout attend [delegate]. */
class DeferredChatRepository(private val delegate: Deferred<ChatRepository>) : ChatRepository {

    private fun <T> flowOf(block: (ChatRepository) -> Flow<T>): Flow<T> = flow { emitAll(block(delegate.await())) }

    override val meshStatus: Flow<MeshStatus> = flowOf { it.meshStatus }

    override fun observeConversations(): Flow<List<Conversation>> = flowOf { it.observeConversations() }

    override fun observeMessages(conversationId: ConversationId): Flow<List<ChatMessage>> =
        flowOf { it.observeMessages(conversationId) }

    override suspend fun sendText(conversationId: ConversationId, body: String): ChatMessage =
        delegate.await().sendText(conversationId, body)

    override suspend fun markRead(conversationId: ConversationId) = delegate.await().markRead(conversationId)

    override suspend fun deleteLocally(messageId: MessageId) = delegate.await().deleteLocally(messageId)

    override suspend fun createRoom(title: String, admission: AdmissionPolicy): RoomInvite =
        delegate.await().createRoom(title, admission)

    override suspend fun requestToJoin(inviteLink: String): PendingJoin = delegate.await().requestToJoin(inviteLink)

    override suspend fun addContact(contactLink: String): ConversationId = delegate.await().addContact(contactLink)

    override suspend fun myContactLink(): String = delegate.await().myContactLink()

    override fun observePendingJoins(): Flow<List<PendingJoin>> = flowOf { it.observePendingJoins() }

    override fun observeJoinRequests(): Flow<List<JoinRequest>> = flowOf { it.observeJoinRequests() }

    override suspend fun approveJoinRequest(requestId: String) = delegate.await().approveJoinRequest(requestId)

    override suspend fun rejectJoinRequest(requestId: String) = delegate.await().rejectJoinRequest(requestId)

    override fun observeRoomDetails(conversationId: ConversationId): Flow<RoomDetails?> =
        flowOf { it.observeRoomDetails(conversationId) }

    override suspend fun leave(conversationId: ConversationId) = delegate.await().leave(conversationId)
}
