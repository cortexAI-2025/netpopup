package com.netpopup.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModerationUtilsTest {

    @Test
    fun `detects banned words case-insensitively`() {
        assertTrue(ModerationUtils.containsBannedWords("This is a SCAM"))
        assertTrue(ModerationUtils.containsBannedWords("click here now"))
    }

    @Test
    fun `accepts normal text`() {
        assertFalse(ModerationUtils.containsBannedWords("Anyone up for coffee?"))
    }

    @Test
    fun `sanitize trims and truncates to 500 chars`() {
        assertEquals("hello", ModerationUtils.sanitize("  hello \n"))
        assertEquals(500, ModerationUtils.sanitize("a".repeat(800)).length)
    }

    @Test
    fun `room codes use the unambiguous alphabet`() {
        repeat(200) {
            val code = ModerationUtils.generateRoomCode()
            assertEquals(6, code.length)
            assertTrue(code, code.matches(Regex("[A-HJ-NP-Z2-9]{6}")))
        }
    }
}
