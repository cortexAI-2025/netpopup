package com.netpopup.dtn.presentation

import com.netpopup.dtn.chat.ConversationKind
import com.netpopup.dtn.chat.DeliveryState
import com.netpopup.dtn.chat.InMemoryChatRepository
import com.netpopup.dtn.chat.LOCAL_CONVERSATION_ID
import com.netpopup.dtn.chat.MeshStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationPresenterTest {
    private val blocked = MutableStateFlow<Set<String>>(emptySet())
    private val blockedLog = ArrayList<String>()

    private fun TestScope.presenter(repo: InMemoryChatRepository, id: com.netpopup.dtn.chat.ConversationId = LOCAL_CONVERSATION_ID) =
        ConversationPresenter(repo, id, backgroundScope, blocked, { blockedLog += it; blocked.value += it }) { currentTime }

    @Test fun showsLocalConversationAndMessages() = runTest {
        val repo = InMemoryChatRepository(now = { currentTime })
        val p = presenter(repo)
        repo.simulateIncomingMessage(LOCAL_CONVERSATION_ID, "salut", "peer-1", "Alice")
        runCurrent()
        val s = p.state.value
        assertFalse(s.isLoading)
        assertEquals(ConversationKind.LOCAL_PUBLIC, s.conversation!!.kind)
        assertEquals(listOf("salut"), s.messages.map { it.body })
        assertNull(s.error)
    }

    @Test fun sentMessageAppearsAtOnceAsQueuedEvenWithoutPeers() = runTest {
        val repo = InMemoryChatRepository(now = { currentTime })
        val p = presenter(repo)
        p.sendMessage("  bonjour  ")
        runCurrent()
        val m = p.state.value.messages.single()
        assertEquals("bonjour", m.body)
        assertEquals(DeliveryState.QUEUED, m.deliveryState)
        assertEquals(0, p.state.value.peersInRange)
    }

    @Test fun invalidMessagesShowAnErrorWithoutCountingTowardsTheRate() = runTest {
        val repo = InMemoryChatRepository(now = { currentTime })
        val p = presenter(repo)
        p.sendMessage("   ")
        runCurrent()
        assertNotNull(p.state.value.error)
        p.clearError()
        p.sendMessage("ok") // aucun délai à attendre : le texte refusé n'a pas consommé le débit
        runCurrent()
        assertNull(p.state.value.error)
        assertEquals(1, p.state.value.messages.size)
    }

    @Test fun rateLimitedToOneMessagePerSecond() = runTest {
        val repo = InMemoryChatRepository(now = { currentTime })
        val p = presenter(repo)
        p.sendMessage("un"); runCurrent()
        p.sendMessage("deux"); runCurrent()
        assertEquals(ConversationPresenter.TOO_FAST, p.state.value.error)
        assertEquals(1, p.state.value.messages.size)
        advanceTimeBy(1_000); runCurrent()
        p.sendMessage("trois"); runCurrent()
        assertNull(p.state.value.error)
        assertEquals(listOf("un", "trois"), p.state.value.messages.map { it.body })
    }

    @Test fun messagesFromBlockedSendersAreHiddenByKeyNotByName() = runTest {
        val repo = InMemoryChatRepository(now = { currentTime })
        val p = presenter(repo)
        repo.simulateIncomingMessage(LOCAL_CONVERSATION_ID, "pénible", "key-bad", "Alice")
        repo.simulateIncomingMessage(LOCAL_CONVERSATION_ID, "sympa", "key-good", "Alice") // même pseudo
        runCurrent()
        assertEquals(2, p.state.value.messages.size)
        p.blockSender("key-bad")
        runCurrent()
        assertEquals(listOf("sympa"), p.state.value.messages.map { it.body })
        assertEquals(setOf("key-bad"), p.state.value.blockedSenders)
        assertEquals(listOf("key-bad"), blockedLog)
    }

    @Test fun ownMessagesAreNeverHidden() = runTest {
        val repo = InMemoryChatRepository(selfKey = "me", now = { currentTime })
        val p = presenter(repo)
        blocked.value = setOf("me")
        p.sendMessage("moi"); runCurrent()
        assertEquals(listOf("moi"), p.state.value.messages.map { it.body })
    }

    @Test fun expiredMessagesDisappearByThemselves() = runTest {
        val repo = InMemoryChatRepository(now = { currentTime })
        val p = presenter(repo)
        repo.simulateIncomingMessage(LOCAL_CONVERSATION_ID, "éphémère")
        runCurrent()
        assertEquals(1, p.state.value.messages.size)
        advanceTimeBy(6 * 3600 * 1000L + 1); runCurrent()
        assertTrue(p.state.value.messages.isEmpty())
    }

    @Test fun markReadMarksReceivedMessages() = runTest {
        val repo = InMemoryChatRepository(now = { currentTime })
        val p = presenter(repo)
        repo.simulateIncomingMessage(LOCAL_CONVERSATION_ID, "lis-moi")
        runCurrent()
        assertNull(p.state.value.messages.single().readAt)
        p.markRead(); runCurrent()
        assertNotNull(p.state.value.messages.single().readAt)
    }

    @Test fun deleteLocallyRemovesOnlyThatMessage() = runTest {
        val repo = InMemoryChatRepository(now = { currentTime })
        val p = presenter(repo)
        val a = repo.simulateIncomingMessage(LOCAL_CONVERSATION_ID, "a")
        repo.simulateIncomingMessage(LOCAL_CONVERSATION_ID, "b")
        runCurrent()
        p.deleteLocally(a.id); runCurrent()
        assertEquals(listOf("b"), p.state.value.messages.map { it.body })
    }

    @Test fun peersInRangeFollowTheMesh() = runTest {
        val repo = InMemoryChatRepository(now = { currentTime })
        val p = presenter(repo)
        repo.setMeshStatus(MeshStatus(isRunning = true, peersInRange = 3)); runCurrent()
        assertEquals(3, p.state.value.peersInRange)
    }

    @Test fun privateRoomShowsDetailsAndRotation() = runTest {
        val repo = InMemoryChatRepository(selfKey = "me", selfName = "Moi", now = { currentTime })
        val invite = repo.createRoom("Voisins")
        val p = presenter(repo, invite.conversationId)
        runCurrent()
        val details = p.state.value.roomDetails!!
        assertEquals(listOf("Moi"), details.members.map { it.displayName })
        assertTrue(details.members.single().isSelf)
        assertTrue(details.securityFingerprint.isNotBlank())
        assertFalse(details.keyRotationPending)
        // La room locale n'a pas de détails de room privée.
        assertNull(presenter(repo).also { runCurrent() }.state.value.roomDetails)
    }

    @Test fun unknownConversationIsReported() = runTest {
        val repo = InMemoryChatRepository(now = { currentTime })
        val p = presenter(repo, com.netpopup.dtn.chat.ConversationId("nope"))
        runCurrent()
        assertEquals(ConversationPresenter.CONVERSATION_NOT_FOUND, p.state.value.error)
        assertFalse(p.state.value.isLoading)
    }

    @Test fun leavingARoomReportsItAsGone() = runTest {
        val repo = InMemoryChatRepository(now = { currentTime })
        val id = repo.createRoom("Temp").conversationId
        val p = presenter(repo, id)
        runCurrent()
        assertNotNull(p.state.value.conversation)
        repo.leave(id); runCurrent()
        assertNull(p.state.value.conversation)
        assertEquals(ConversationPresenter.CONVERSATION_NOT_FOUND, p.state.value.error)
    }
}
