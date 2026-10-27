package com.netpopup.dtn.presentation

import com.netpopup.dtn.chat.ChatRepository
import com.netpopup.dtn.chat.ChatMessage
import com.netpopup.dtn.chat.ConversationId
import com.netpopup.dtn.chat.InMemoryChatRepository
import com.netpopup.dtn.chat.LOCAL_CONVERSATION_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActionFailureTest {
    @Test fun storageFailuresOnSendAndReadAreShownWithoutEscapingThePresenter() = runTest {
        val base = InMemoryChatRepository()
        val repo = object : ChatRepository by base {
            override suspend fun sendText(conversationId: ConversationId, body: String): ChatMessage =
                throw IllegalStateException("storage failure")
            override suspend fun markRead(conversationId: ConversationId) = throw IllegalStateException("read failure")
        }
        val presenter = ConversationPresenter(repo, LOCAL_CONVERSATION_ID, backgroundScope, flowOf(emptySet()), {})
        runCurrent()
        presenter.sendMessage("Hello"); runCurrent()
        assertEquals(ConversationPresenter.OPERATION_FAILED, presenter.state.value.error)
        presenter.clearError()
        presenter.markRead(); runCurrent()
        assertEquals(ConversationPresenter.OPERATION_FAILED, presenter.state.value.error)
    }

    @Test fun failedRoomActionIsShownWithoutCrashingTheRoomsScreen() = runTest {
        val base = InMemoryChatRepository()
        val repo = object : ChatRepository by base {
            override suspend fun approveJoinRequest(requestId: String) = throw IllegalStateException("write failure")
        }
        val presenter = RoomsPresenter(repo, backgroundScope)
        runCurrent()
        presenter.approve("request"); runCurrent()
        assertEquals(RoomsPresenter.GENERIC_ERROR, presenter.state.value.error)
    }

    @Test fun aBrokenConversationStreamStopsLoadingAndShowsAnError() = runTest {
        val base = InMemoryChatRepository()
        val repo = object : ChatRepository by base {
            override fun observeConversations() = flow<List<com.netpopup.dtn.chat.Conversation>> {
                throw IllegalStateException("query failure")
            }
        }
        val presenter = ConversationPresenter(repo, LOCAL_CONVERSATION_ID, backgroundScope, flowOf(emptySet()), {})
        runCurrent()
        assertEquals(false, presenter.state.value.isLoading)
        assertEquals(ConversationPresenter.OPERATION_FAILED, presenter.state.value.error)
    }

    @Test fun cancellationIsNotDisplayedAsAnActionFailure() = runTest {
        val base = InMemoryChatRepository()
        val repo = object : ChatRepository by base {
            override suspend fun approveJoinRequest(requestId: String) = throw CancellationException("screen closed")
        }
        val presenter = RoomsPresenter(repo, backgroundScope)
        runCurrent()
        presenter.approve("request"); runCurrent()
        assertNull(presenter.state.value.error)
    }
}
