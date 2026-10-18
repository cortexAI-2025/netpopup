package com.netpopup.dtn.chat

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Vérifie le contrat de [ChatRepository] sur le faux en mémoire. */
class InMemoryChatRepositoryTest {

    private var clock = 1_000_000L
    private val repo = InMemoryChatRepository(now = { clock }, random = Random(42))
    private val local = InMemoryChatRepository.LOCAL_ID

    @Test
    fun `local public room always exists and is public`() = runTest {
        val conversations = repo.observeConversations().first()
        val localRoom = conversations.single { it.id == local }
        assertTrue(localRoom.isPublic)
        assertEquals(ConversationKind.LOCAL_PUBLIC, localRoom.kind)
    }

    @Test
    fun `sent message appears immediately as queued`() = runTest {
        val sent = repo.sendText(local, "  bonjour  ")
        val messages = repo.observeMessages(local).first()
        assertEquals(listOf(sent), messages)
        assertEquals("bonjour", sent.body)
        assertEquals(DeliveryState.QUEUED, sent.deliveryState)
        assertTrue(sent.isOwn)
        assertEquals(clock + MessageRules.DEFAULT_LIFETIME_MS, sent.expiresAt)
    }

    @Test
    fun `invalid message and unknown conversation are rejected`() = runTest {
        assertFails<InvalidMessageException> { repo.sendText(local, "   ") }
        assertFails<UnknownConversationException> { repo.sendText(ConversationId("nope"), "hi") }
    }

    @Test
    fun `expired messages are not returned`() = runTest {
        repo.sendText(local, "éphémère")
        clock += MessageRules.DEFAULT_LIFETIME_MS + 1
        assertTrue(repo.observeMessages(local).first().isEmpty())
    }

    @Test
    fun `a collected list drops a message when it expires`() = runTest {
        // Horloge du dépôt = temps virtuel du test
        val timed = InMemoryChatRepository(now = { testScheduler.currentTime })
        val emissions = mutableListOf<List<ChatMessage>>()
        backgroundScope.launch { timed.observeMessages(local).toList(emissions) }

        timed.sendText(local, "éphémère")
        runCurrent()
        assertEquals(1, emissions.last().size)

        advanceTimeBy(MessageRules.DEFAULT_LIFETIME_MS + 1)
        runCurrent()
        assertTrue(emissions.last().isEmpty())
    }

    @Test
    fun `messages are ordered by sender timestamp`() = runTest {
        repo.simulateIncomingMessage(local, "second", sentAt = clock + 10)
        repo.simulateIncomingMessage(local, "first", sentAt = clock + 5)
        assertEquals(listOf("first", "second"), repo.observeMessages(local).first().map { it.body })
    }

    @Test
    fun `mark read only touches received messages of that conversation`() = runTest {
        val own = repo.sendText(local, "moi")
        val incoming = repo.simulateIncomingMessage(local, "toi")
        repo.markRead(local)
        val byId = repo.observeMessages(local).first().associateBy { it.id }
        assertEquals(null, byId.getValue(own.id).readAt)
        assertEquals(clock, byId.getValue(incoming.id).readAt)
    }

    @Test
    fun `room keeps at most 1000 messages, dropping the oldest`() = runTest {
        repeat(MessageRules.MAX_MESSAGES_PER_ROOM + 5) { i ->
            repo.simulateIncomingMessage(local, "m$i", sentAt = clock + i)
        }
        val messages = repo.observeMessages(local).first()
        assertEquals(MessageRules.MAX_MESSAGES_PER_ROOM, messages.size)
        assertEquals("m5", messages.first().body)
    }

    @Test
    fun `created room has a valid code and its admission policy`() = runTest {
        val invite = repo.createRoom("Amis", AdmissionPolicy.CREATOR_ONLY)
        assertEquals(invite.code, MessageRules.normalizeRoomCode(invite.code))
        val room = repo.observeConversations().first().single { it.id == invite.conversationId }
        assertEquals(ConversationKind.ROOM, room.kind)
        assertEquals(AdmissionPolicy.CREATOR_ONLY, room.admission)
        assertFalse(room.isPublic)
    }

    @Test
    fun `join request stays pending until accepted`() = runTest {
        repo.requestToJoin("xk7b2q")
        assertEquals(listOf("XK7B2Q"), repo.observePendingJoins().first().map { it.code })

        val roomId = repo.simulateJoinAccepted("XK7B2Q", "Amis")
        assertTrue(repo.observePendingJoins().first().isEmpty())
        assertNotNull(repo.observeConversations().first().singleOrNull { it.id == roomId })
    }

    @Test
    fun `incoming join request can be approved`() = runTest {
        val invite = repo.createRoom("Amis")
        val request = repo.simulateJoinRequest(invite.conversationId, "Fox_1234")
        assertEquals(listOf(request), repo.observeJoinRequests().first())
        repo.approveJoinRequest(request.id)
        assertTrue(repo.observeJoinRequests().first().isEmpty())
    }

    @Test
    fun `leaving removes room and its messages but local room stays`() = runTest {
        val invite = repo.createRoom("Amis")
        repo.sendText(invite.conversationId, "salut")
        repo.leave(invite.conversationId)
        repo.leave(local)

        val ids = repo.observeConversations().first().map { it.id }
        assertEquals(listOf(local), ids)
        assertTrue(repo.observeMessages(invite.conversationId).first().isEmpty())
    }

    @Test
    fun `leaving a room drops its pending join requests`() = runTest {
        val kept = repo.createRoom("Gardée")
        val left = repo.createRoom("Quittée")
        val keptRequest = repo.simulateJoinRequest(kept.conversationId)
        repo.simulateJoinRequest(left.conversationId)

        repo.leave(left.conversationId)
        assertEquals(listOf(keptRequest), repo.observeJoinRequests().first())
    }

    /** `inline` : le bloc peut appeler des fonctions `suspend` depuis `runTest`. */
    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw e
        }
        throw AssertionError("Expected ${T::class.simpleName}")
    }
}
