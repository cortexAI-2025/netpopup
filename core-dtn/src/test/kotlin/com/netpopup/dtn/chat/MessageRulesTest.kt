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

    @Test
    fun `room code is upper-cased and stripped of separators`() {
        assertEquals("XK7B2Q", MessageRules.normalizeRoomCode(" xk7-b2q "))
    }

    @Test
    fun `room code with ambiguous characters or wrong length is rejected`() {
        listOf("XK7B2", "XK7B2QQ", "XK0B2Q", "XKIB2Q", "XK7B2!").forEach { code ->
            assertThrows(code, InvalidRoomCodeException::class.java) { MessageRules.normalizeRoomCode(code) }
        }
    }
}
