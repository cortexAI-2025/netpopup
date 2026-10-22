package com.netpopup.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IdentityTest {
    @Test fun identityIsGeneratedOnceAndReloaded() {
        val vault = InMemoryIdentityVault()
        val first = LocalIdentity.loadOrCreate(vault)
        val second = LocalIdentity.loadOrCreate(vault)
        assertArrayEquals(first.publicKey.serialize(), second.publicKey.serialize())
        assertEquals(first.registrationId, second.registrationId)
        assertEquals(first.fingerprint, second.fingerprint)
    }

    @Test fun differentInstallsHaveDifferentFingerprints() {
        val a = LocalIdentity.loadOrCreate(InMemoryIdentityVault())
        val b = LocalIdentity.loadOrCreate(InMemoryIdentityVault())
        assertNotEquals(a.fingerprint, b.fingerprint)
    }

    @Test fun fingerprintIsTwoLinesOfFiveGroups() {
        val lines = LocalIdentity.loadOrCreate(InMemoryIdentityVault()).fingerprint.lines()
        assertEquals(2, lines.size)
        lines.forEach { assertEquals(5, it.split(" ").size) }
    }
}
