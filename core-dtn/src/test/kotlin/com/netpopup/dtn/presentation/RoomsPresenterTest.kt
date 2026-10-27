package com.netpopup.dtn.presentation

import com.netpopup.dtn.chat.AdmissionPolicy
import com.netpopup.dtn.chat.ConversationKind
import com.netpopup.dtn.chat.InMemoryChatRepository
import com.netpopup.dtn.chat.LOCAL_CONVERSATION_ID
import com.netpopup.dtn.chat.MessageRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
        assertEquals(s.createdInvite!!.link, MessageRules.normalizeInviteLink(s.createdInvite!!.link))
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

    private val link = MessageRules.ROOM_LINK_PREFIX + "Qk7B2".repeat(19)

    @Test fun requestToJoinShowsAPendingRequestUntilAccepted() = runTest {
        val repo = InMemoryChatRepository()
        val p = presenter(repo)
        p.onInviteLinkChanged(link)
        p.requestToJoin(); runCurrent()
        assertEquals(1, p.state.value.pendingJoins.size)
        assertEquals("", p.state.value.inviteLink)
        assertTrue(p.state.value.rooms.isEmpty()) // le lien ne donne pas accès

        repo.simulateJoinAccepted(link, "Voisins"); runCurrent()
        assertTrue(p.state.value.pendingJoins.isEmpty())
        assertEquals(listOf("Voisins"), p.state.value.rooms.map { it.title })
    }

    @Test fun oldSixCharacterCodesAndGarbageAreRefused() = runTest {
        val p = presenter(InMemoryChatRepository())
        for (bad in listOf("XK7B2Q", "netpopup://room/short", "https://x.y/" + "A".repeat(100))) {
            p.onInviteLinkChanged(bad)
            p.requestToJoin(); runCurrent()
            assertNotNull(bad, p.state.value.error)
            p.clearError()
        }
        assertTrue(p.state.value.pendingJoins.isEmpty())
        assertEquals(false, p.state.value.isLoading)
    }

    @Test fun contactsAreAddedFromTheirLinkAndOpenTheConversation() = runTest {
        val repo = InMemoryChatRepository()
        val p = presenter(repo)
        runCurrent()
        assertNotNull(p.state.value.myContactLink)
        p.onContactLinkChanged(MessageRules.CONTACT_LINK_PREFIX + "C".repeat(300))
        p.addContact(); runCurrent()
        val id = p.state.value.navigateTo!!
        assertEquals(ConversationKind.DIRECT, repo.observeConversations().first().single { it.id == id }.kind)
        p.onContactLinkChanged(link) // une invitation de room n'est pas un contact
        p.addContact(); runCurrent()
        assertNotNull(p.state.value.error)
    }

    @Test fun existingContactsRemainReachableWhenTheRoomsScreenIsReopened() = runTest {
        val repo = InMemoryChatRepository()
        val id = repo.addContact(MessageRules.CONTACT_LINK_PREFIX + "C".repeat(300))
        repo.createRoom("Neighbours")
        val first = presenter(repo)
        runCurrent()
        assertEquals(listOf(id), first.state.value.contacts.map { it.id })
        assertTrue(first.state.value.rooms.none { it.id == id })
        val reopened = presenter(repo)
        runCurrent()
        assertEquals(listOf(id), reopened.state.value.contacts.map { it.id })
        assertNull(reopened.state.value.navigateTo)
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
