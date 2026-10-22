package com.netpopup.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Stockage durable de la paire de clés d'identité sérialisée. L'implémentation de
 * production (SQLCipher + Android Keystore) vient avec mesh-13 ; la clé privée ne
 * doit jamais en sortir en clair.
 */
interface IdentityVault {
    fun load(): ByteArray?
    fun save(serializedIdentity: ByteArray, registrationId: Int)
    fun registrationId(): Int?
}

class InMemoryIdentityVault : IdentityVault {
    private var identity: ByteArray? = null
    private var registration: Int? = null
    override fun load() = identity?.copyOf()
    override fun save(serializedIdentity: ByteArray, registrationId: Int) {
        identity = serializedIdentity.copyOf()
        registration = registrationId
    }
    override fun registrationId() = registration
}

/** Identité de l'appareil : générée une fois à l'installation, relue ensuite. */
class LocalIdentity(val keyPair: IdentityKeyPair, val registrationId: Int) {
    val publicKey: IdentityKey get() = keyPair.publicKey
    val fingerprint: String get() = Fingerprints.of(publicKey)

    companion object {
        private val random = SecureRandom()

        fun loadOrCreate(vault: IdentityVault): LocalIdentity {
            val stored = vault.load()
            val registration = vault.registrationId()
            if (stored != null && registration != null) {
                return LocalIdentity(IdentityKeyPair(stored), registration)
            }
            val pair = IdentityKeyPair.generate()
            val id = random.nextInt(1, 16380) // plage de registrationId de libsignal
            vault.save(pair.serialize(), id)
            return LocalIdentity(pair, id)
        }
    }
}

object Fingerprints {
    /** Empreinte affichable : SHA-256 de la clé publique sérialisée, 5 groupes de 4 hexa × 2 lignes. */
    fun of(key: IdentityKey): String {
        val hex = MessageDigest.getInstance("SHA-256").digest(key.serialize())
            .joinToString("") { "%02x".format(it) }
        return hex.take(40).chunked(4).chunked(5).joinToString("\n") { it.joinToString(" ") }
    }
}
