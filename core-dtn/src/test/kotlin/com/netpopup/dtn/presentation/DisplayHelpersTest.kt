package com.netpopup.dtn.presentation

import com.netpopup.dtn.chat.ConversationId
import com.netpopup.dtn.chat.ConversationKind
import com.netpopup.dtn.chat.DeliveryState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayHelpersTest {
    @After fun reset() = ActiveConversation.current.value?.let(ActiveConversation::leave) ?: Unit

    @Test fun ownMessagesShowTheirStateAndOthersShowNothing() {
        assertEquals("Queued", DeliveryDisplay.label(DeliveryState.QUEUED, ConversationKind.ROOM, true))
        assertEquals("Relayed", DeliveryDisplay.label(DeliveryState.RELAYED, ConversationKind.ROOM, true))
        assertNull(DeliveryDisplay.label(DeliveryState.RECEIVED, ConversationKind.ROOM, false))
        assertNull(DeliveryDisplay.label(DeliveryState.QUEUED, ConversationKind.ROOM, false))
    }

    @Test fun deliveredExistsOnlyInOneToOne() {
        assertEquals("Delivered", DeliveryDisplay.label(DeliveryState.ACKNOWLEDGED, ConversationKind.DIRECT, true))
        for (kind in listOf(ConversationKind.ROOM, ConversationKind.LOCAL_PUBLIC, null)) {
            assertEquals("Relayed", DeliveryDisplay.label(DeliveryState.ACKNOWLEDGED, kind, true))
        }
    }

    @Test fun onlyTheLatestConversationStaysActive() {
        val a = ConversationId("a")
        val b = ConversationId("b")
        ActiveConversation.enter(a)
        assertTrue(ActiveConversation.isShown(a))
        ActiveConversation.enter(b) // navigation rapide : b prend la place avant que a ne soit quittée
        ActiveConversation.leave(a)
        assertTrue(ActiveConversation.isShown(b))
        ActiveConversation.leave(b)
        assertFalse(ActiveConversation.isShown(b))
        assertNull(ActiveConversation.current.value)
    }
}
