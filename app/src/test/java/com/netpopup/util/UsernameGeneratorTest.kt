package com.netpopup.util

import org.junit.Assert.assertTrue
import org.junit.Test

class UsernameGeneratorTest {

    @Test
    fun `generates Word_NNNN names that fit the Firestore rule`() {
        repeat(200) {
            val name = UsernameGenerator.generate()
            assertTrue(name, name.matches(Regex("[A-Za-z]+_[1-9][0-9]{3}")))
            assertTrue(name, name.length <= 32)
        }
    }
}
