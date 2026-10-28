package com.netpopup.startup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netpopup.crypto.InMemoryIdentityVault
import com.netpopup.crypto.LocalIdentity
import com.netpopup.crypto.PairwiseSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Execute against Android's actual boot classes, not the build machine's Java 17 APIs. */
@RunWith(AndroidJUnit4::class)
class CryptoCompatibilityInstrumentedTest {
    @Test fun freshIdentityHasValidRegistrationIdAndReloadsWithoutChangingKeys() {
        repeat(16) {
            val vault = InMemoryIdentityVault()
            val identity = LocalIdentity.loadOrCreate(vault)
            assertTrue(identity.registrationId in 1 until 16380)
            val restored = LocalIdentity.loadOrCreate(vault)
            assertEquals(identity.registrationId, restored.registrationId)
            assertArrayEquals(identity.keyPair.serialize(), restored.keyPair.serialize())
        }
    }

    @Test fun directConversationKeysAreSymmetricOnThisAndroidVersion() {
        val alice = LocalIdentity.loadOrCreate(InMemoryIdentityVault())
        val bob = LocalIdentity.loadOrCreate(InMemoryIdentityVault())
        val ab = PairwiseSession.pairKeys(alice.keyPair, bob.publicKey)
        val ba = PairwiseSession.pairKeys(bob.keyPair, alice.publicKey)
        assertArrayEquals(ab.tagKey, ba.tagKey)
        assertArrayEquals(ab.envKey, ba.envKey)
    }
}
