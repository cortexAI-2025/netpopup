package com.netpopup.dtn.presentation

import com.netpopup.dtn.chat.AdmissionPolicy
import com.netpopup.dtn.chat.ConversationKind
import com.netpopup.dtn.chat.InMemoryChatRepository
import com.netpopup.dtn.chat.LOCAL_CONVERSATION_ID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomsPresenterTest {
    private fun TestScope.presenter(repo: InMemoryChatRepository) = RoomsPresenter(repo, backgroundScope)

    @Test fun createRoomUsesTitleAndPolicyThenNavigatesOnce() = runTest {
        val repo = InMemoryChatRepository()
        val p = presenter(repo)
        p.onTitleChanged("  Voisins  ")
        p.onAdmissionChanged(AdmissionPolicy.CREATOR_ONLY)
        p.createRoom(); runCurrent()
        val s = p.state.value
        assertEquals(6, s.createdInvite!!.code.length)
        assertEquals(s.createdInvite!!.conversationId, s.navigateTo)
        assertEquals(AdmissionPolicy.CREATOR_ONLY, s.rooms.single().admission)
        assertEquals("Voisins", s.rooms.single().title)
        p.onNavigated()
        assertNull(p.state.value.navigateTo)
    }

    @Test fun roomWithoutNameIsRefused() = runTest {
        val p = presenter(InMemoryChatRepository())
        p.createRoom(); runCurrent()
        assertEquals(RoomsPresenter.TITLE_REQUIRED, p.state.value.error)
        assertTrue(p.state.value.rooms.isEmpty())
    }

    @Test fun roomsListIgnoresTheLocalPublicRoom() = runTest {
        val repo = InMemoryChatRepository()
        val p = presenter(repo)
        runCurrent()
        assertTrue(p.state.value.rooms.isEmpty())
        repo.createRoom("A"); runCurrent()
        assertEquals(listOf(ConversationKind.ROOM), p.state.value.rooms.map { it.kind })
        assertTrue(p.state.value.rooms.none { it.id == LOCAL_CONVERSATION_ID })
    }

    @Test fun requestToJoinShowsAPendingRequestUntilAccepted() = runTest {
        val repo = InMemoryChatRepository()
        val p = presenter(repo)
        p.onJoinCodeChanged("xk7b2q")
        assertEquals("XK7B2Q", p.state.value.joinCode)
        p.requestToJoin(); runCurrent()
        assertEquals(listOf("XK7B2Q"), p.state.value.pendingJoins.map { it.code })
        assertEquals("", p.state.value.joinCode)
        assertTrue(p.state.value.rooms.isEmpty()) // le code ne donne pas accès

        repo.simulateJoinAccepted("XK7B2Q", "Voisins"); runCurrent()
        assertTrue(p.state.value.pendingJoins.isEmpty())
        assertEquals(listOf("Voisins"), p.state.value.rooms.map { it.title })
    }

    @Test fun malformedCodeShowsAnError() = runTest {
        val p = presenter(InMemoryChatRepository())
        p.onJoinCodeChanged("ABC")
        p.requestToJoin(); runCurrent()
        assertNotNull(p.state.value.error)
        assertTrue(p.state.value.pendingJoins.isEmpty())
        assertEquals(false, p.state.value.isLoading)
    }

    @Test fun incomingRequestsCanBeApprovedOrRejected() = runTest {
        val repo = InMemoryChatRepository()
        val room = repo.createRoom("R").conversationId
        val p = presenter(repo)
        val a = repo.simulateJoinRequest(room, "Alice")
        val b = repo.simulateJoinRequest(room, "Bob")
        runCurrent()
        assertEquals(2, p.state.value.joinRequests.size)
        p.approve(a.id); p.reject(b.id); runCurrent()
        assertTrue(p.state.value.joinRequests.isEmpty())
    }

    @Test fun leavingRemovesTheRoomAndItsRequests() = runTest {
        val repo = InMemoryChatRepository()
        val room = repo.createRoom("R").conversationId
        val p = presenter(repo)
        repo.simulateJoinRequest(room)
        runCurrent()
        p.leave(room); runCurrent()
        assertTrue(p.state.value.rooms.isEmpty())
        assertTrue(p.state.value.joinRequests.isEmpty())
    }
}
