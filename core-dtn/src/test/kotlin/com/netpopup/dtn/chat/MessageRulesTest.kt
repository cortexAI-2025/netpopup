package com.netpopup.dtn.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MessageRulesTest {

    @Test
    fun `body is trimmed`() {
        assertEquals("salut", MessageRules.normalizeBody("  salut \n"))
    }

    @Test
    fun `blank body is rejected`() {
        assertThrows(InvalidMessageException::class.java) { MessageRules.normalizeBody(" \n\t ") }
    }

    @Test
    fun `body of exactly 500 characters is accepted, 501 rejected`() {
        assertEquals(500, MessageRules.normalizeBody("a".repeat(500)).length)
        assertThrows(InvalidMessageException::class.java) { MessageRules.normalizeBody("a".repeat(501)) }
    }

    private val validRoomLink = MessageRules.ROOM_LINK_PREFIX + "A".repeat(91)

    @Test
    fun `well formed invite link is accepted and trimmed`() {
        assertEquals(validRoomLink, MessageRules.normalizeInviteLink("  $validRoomLink \n"))
    }

    @Test
    fun `invite link of the wrong shape is rejected`() {
        val bad = listOf(
            "",
            "XK7B2Q",                                                // l'ancien code à 6 caractères
            "https://example.com/room/" + "A".repeat(91),            // autre schéma
            MessageRules.CONTACT_LINK_PREFIX + "A".repeat(200),      // lien de contact, pas d'invitation
            MessageRules.ROOM_LINK_PREFIX + "A".repeat(90),          // tronqué
            MessageRules.ROOM_LINK_PREFIX + "A".repeat(80) + "!!!!" + "A".repeat(10), // alphabet
            MessageRules.ROOM_LINK_PREFIX + "A".repeat(600),         // trop long
        )
        bad.forEach { link ->
            assertThrows(link.take(40), InvalidInviteException::class.java) { MessageRules.normalizeInviteLink(link) }
        }
    }

    @Test
    fun `contact link is checked the same way and not mistaken for an invitation`() {
        val contact = MessageRules.CONTACT_LINK_PREFIX + "B".repeat(2_400)
        assertEquals(contact, MessageRules.normalizeContactLink(contact))
        assertThrows(InvalidInviteException::class.java) { MessageRules.normalizeContactLink(validRoomLink) }
        assertThrows(InvalidInviteException::class.java) { MessageRules.normalizeContactLink(MessageRules.CONTACT_LINK_PREFIX + "B".repeat(3_100)) }
    }
}
