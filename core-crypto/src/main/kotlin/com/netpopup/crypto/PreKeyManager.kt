package com.netpopup.crypto

import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * Renouvelle la pré-clé signée chaque semaine. L'ancienne reste utilisable 26 h après
 * son remplacement : durée de vie maximale d'un message (24 h) plus la tolérance
 * d'horloge (2 h), pour qu'un premier message en vol reste déchiffrable.
 *
 * Limite : l'interface `KyberPreKeyStore` de libsignal ne sait pas supprimer ; les
 * anciennes pré-clés Kyber restent dans le store jusqu'à ce que celui de mesh-13 les purge.
 */
class PreKeyManager(
    private val store: SignalProtocolStore,
    private val identity: LocalIdentity,
    private val withKyber: Boolean = true,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Crée la pré-clé courante si besoin, applique la rotation, purge les périmées. */
    fun rotateIfNeeded() {
        val now = nowMillis()
        val records = store.loadSignedPreKeys().sortedBy { it.timestamp }
        val current = records.lastOrNull()
        if (current == null || now - current.timestamp >= ROTATION_MILLIS) createNew(now, (current?.id ?: 0) + 1)
        val sorted = store.loadSignedPreKeys().sortedBy { it.timestamp }
        for (i in 0 until sorted.size - 1) {
            if (now - sorted[i + 1].timestamp >= GRACE_MILLIS) store.removeSignedPreKey(sorted[i].id)
        }
    }

    fun currentBundle(name: String): ContactBundle {
        rotateIfNeeded()
        val spk = store.loadSignedPreKeys().maxByOrNull { it.timestamp }!!
        val kyber = if (withKyber) runCatching { store.loadKyberPreKey(spk.id) }.getOrNull() else null
        return ContactBundle(
            name = name,
            registrationId = identity.registrationId,
            identityKey = identity.publicKey,
            signedPreKeyId = spk.id,
            signedPreKey = spk.keyPair.publicKey,
            signedPreKeySignature = spk.signature,
            kyberPreKeyId = kyber?.id,
            kyberPreKey = kyber?.keyPair?.publicKey,
            kyberPreKeySignature = kyber?.signature,
        )
    }

    private fun createNew(now: Long, id: Int) {
        val pair = Curve.generateKeyPair()
        val signature = identity.keyPair.privateKey.calculateSignature(pair.publicKey.serialize())
        store.storeSignedPreKey(id, SignedPreKeyRecord(id, now, pair, signature))
        if (withKyber) {
            val kem = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val kemSig = identity.keyPair.privateKey.calculateSignature(kem.publicKey.serialize())
            store.storeKyberPreKey(id, KyberPreKeyRecord(id, now, kem, kemSig))
        }
    }

    companion object {
        const val ROTATION_MILLIS = 7L * 24 * 3600 * 1000
        const val GRACE_MILLIS = 26L * 3600 * 1000
    }
}
