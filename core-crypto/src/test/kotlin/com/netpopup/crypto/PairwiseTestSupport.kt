package com.netpopup.crypto

import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

/** Un appareil de test : identité, store libsignal en mémoire, horloge contrôlée. */
class TestDevice(val name: String, withKyber: Boolean = true, startMillis: Long = 1_700_000_000_000L) {
    var now = startMillis
    val identity = LocalIdentity.loadOrCreate(InMemoryIdentityVault())
    val store = InMemorySignalProtocolStore(identity.keyPair, identity.registrationId)
    val preKeys = PreKeyManager(store, identity, withKyber) { now }
    val session = PairwiseSession(store)
    fun bundle() = preKeys.currentBundle(name)
}
