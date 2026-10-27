package com.netpopup.crypto

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsignedBytesTest {
    @Test fun keepsTheExistingKeyOrderingIncludingUnsignedBytesAndPrefixes() {
        val random = Random(78)
        val values = listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(127), byteArrayOf(-128), byteArrayOf(-1), byteArrayOf(0, -1)) +
            List(64) { ByteArray(random.nextInt(0, 34)).also { random.nextBytes(it) } }
        for (a in values) for (b in values) {
            assertEquals(java.util.Arrays.compareUnsigned(a, b).compareTo(0), UnsignedBytes.compare(a, b).compareTo(0))
        }
        assertTrue(UnsignedBytes.compare(byteArrayOf(-1), byteArrayOf(0)) > 0)
    }
}
