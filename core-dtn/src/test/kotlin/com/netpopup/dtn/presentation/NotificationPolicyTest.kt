package com.netpopup.dtn.presentation

import com.netpopup.dtn.chat.ChatMessage
import com.netpopup.dtn.chat.ConversationId
import com.netpopup.dtn.chat.DeliveryState
import com.netpopup.dtn.chat.MessageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {
    private val room = ConversationId("r1")

    private fun message(own: Boolean = false, sender: String = "peer", conv: ConversationId = room) = ChatMessage(
        MessageId("m"), conv, sender, "Name", "secret body", 0, 1, own, DeliveryState.RECEIVED,
    )

    @Test fun notifiesOnlyForOthersMessagesNotBlockedNotOnScreen() {
        assertTrue(NotificationPolicy.shouldNotify(message(), true, emptySet(), null))
        assertFalse("réglage coupé", NotificationPolicy.shouldNotify(message(), false, emptySet(), null))
        assertFalse("son propre message", NotificationPolicy.shouldNotify(message(own = true), true, emptySet(), null))
        assertFalse("expéditeur bloqué", NotificationPolicy.shouldNotify(message(), true, setOf("peer"), null))
        assertFalse("conversation à l'écran", NotificationPolicy.shouldNotify(message(), true, emptySet(), room))
        assertTrue("autre conversation à l'écran", NotificationPolicy.shouldNotify(message(), true, emptySet(), ConversationId("other")))
    }

    @Test fun textIsFixedAndRevealsNothing() {
        assertEquals("New message", NotificationPolicy.TEXT)
    }

    @Test fun lifetimeChoicesAreClampedToValidOnes() {
        assertEquals(6, LifetimeChoices.DEFAULT_HOURS)
        assertEquals(24, LifetimeChoices.normalize(100))
        assertEquals(1, LifetimeChoices.normalize(0))
        assertEquals(6, LifetimeChoices.normalize(7))
        assertEquals(6 * 3600, LifetimeChoices.toSeconds(6))
        assertTrue(LifetimeChoices.HOURS.max() <= 24)
    }
}
