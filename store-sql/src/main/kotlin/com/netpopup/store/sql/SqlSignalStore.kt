package com.netpopup.store.sql

import com.netpopup.crypto.IdentityVault
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import java.util.UUID

private fun SignalProtocolAddress.key() = "$name:$deviceId"

/** Identité de l'appareil dans la base chiffrée. */
class SqlIdentityVault(private val db: SqlDatabase) : IdentityVault {
    override fun load(): ByteArray? =
        db.query("SELECT key_pair FROM signal_identity WHERE id = 1") { it.blob(0) }.firstOrNull()

    override fun registrationId(): Int? =
        db.query("SELECT registration_id FROM signal_identity WHERE id = 1") { it.int(0) }.firstOrNull()

    override fun save(serializedIdentity: ByteArray, registrationId: Int) {
        db.execute("INSERT OR REPLACE INTO signal_identity (id, key_pair, registration_id) VALUES (1, ?, ?)", serializedIdentity, registrationId)
    }
}

private class SqlIdentityKeyStore(private val db: SqlDatabase, private val pair: IdentityKeyPair, private val registrationId: Int) : IdentityKeyStore {
    override fun getIdentityKeyPair() = pair
    override fun getLocalRegistrationId() = registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val existing = getIdentity(address)
        db.execute("INSERT OR REPLACE INTO signal_trusted_identity (address, identity_key) VALUES (?, ?)", address.key(), identityKey.serialize())
        return if (existing != null && existing != identityKey) IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        else IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }

    /** Confiance à la première utilisation ; un changement de clé n'est jamais accepté en silence. */
    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        val existing = getIdentity(address)
        return existing == null || existing == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        db.query("SELECT identity_key FROM signal_trusted_identity WHERE address = ?", address.key()) { IdentityKey(it.blob(0)) }.firstOrNull()
}

private class SqlPreKeyStore(private val db: SqlDatabase) : PreKeyStore {
    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        db.query("SELECT record FROM signal_pre_key WHERE id = ?", preKeyId) { PreKeyRecord(it.blob(0)) }.firstOrNull()
            ?: throw InvalidKeyIdException("No such pre-key: $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        db.execute("INSERT OR REPLACE INTO signal_pre_key (id, record) VALUES (?, ?)", preKeyId, record.serialize())
    }

    override fun containsPreKey(preKeyId: Int) = db.query("SELECT 1 FROM signal_pre_key WHERE id = ?", preKeyId) { 1 }.isNotEmpty()
    override fun removePreKey(preKeyId: Int) { db.execute("DELETE FROM signal_pre_key WHERE id = ?", preKeyId) }
}

private class SqlSignedPreKeyStore(private val db: SqlDatabase) : SignedPreKeyStore {
    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        db.query("SELECT record FROM signal_signed_pre_key WHERE id = ?", signedPreKeyId) { SignedPreKeyRecord(it.blob(0)) }.firstOrNull()
            ?: throw InvalidKeyIdException("No such signed pre-key: $signedPreKeyId")

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        db.query("SELECT record FROM signal_signed_pre_key ORDER BY id") { SignedPreKeyRecord(it.blob(0)) }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        db.execute("INSERT OR REPLACE INTO signal_signed_pre_key (id, record) VALUES (?, ?)", signedPreKeyId, record.serialize())
    }

    override fun containsSignedPreKey(signedPreKeyId: Int) =
        db.query("SELECT 1 FROM signal_signed_pre_key WHERE id = ?", signedPreKeyId) { 1 }.isNotEmpty()

    override fun removeSignedPreKey(signedPreKeyId: Int) { db.execute("DELETE FROM signal_signed_pre_key WHERE id = ?", signedPreKeyId) }
}

/**
 * Les pré-clés Kyber d'un bundle servent à tous les contacts (le même QR est montré à plusieurs
 * personnes) : « utilisée » est donc un simple marquage, jamais une suppression.
 */
private class SqlKyberPreKeyStore(private val db: SqlDatabase) : KyberPreKeyStore {
    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        db.query("SELECT record FROM signal_kyber_pre_key WHERE id = ?", kyberPreKeyId) { KyberPreKeyRecord(it.blob(0)) }.firstOrNull()
            ?: throw InvalidKeyIdException("No such kyber pre-key: $kyberPreKeyId")

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        db.query("SELECT record FROM signal_kyber_pre_key ORDER BY id") { KyberPreKeyRecord(it.blob(0)) }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        db.execute("INSERT OR REPLACE INTO signal_kyber_pre_key (id, record, used) VALUES (?, ?, 0)", kyberPreKeyId, record.serialize())
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int) =
        db.query("SELECT 1 FROM signal_kyber_pre_key WHERE id = ?", kyberPreKeyId) { 1 }.isNotEmpty()

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int) {
        db.execute("UPDATE signal_kyber_pre_key SET used = 1 WHERE id = ?", kyberPreKeyId)
    }
}

private class SqlSessionStore(private val db: SqlDatabase) : SessionStore {
    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        db.query("SELECT record FROM signal_session WHERE address = ?", address.key()) { SessionRecord(it.blob(0)) }.firstOrNull()
            ?: SessionRecord()

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { a ->
            db.query("SELECT record FROM signal_session WHERE address = ?", a.key()) { SessionRecord(it.blob(0)) }.firstOrNull()
                ?: throw NoSessionException("no session for $a")
        }

    override fun getSubDeviceSessions(name: String): List<Int> =
        db.query("SELECT device_id FROM signal_session WHERE name = ? AND device_id != 1", name) { it.int(0) }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        db.execute(
            "INSERT OR REPLACE INTO signal_session (address, name, device_id, record) VALUES (?, ?, ?, ?)",
            address.key(), address.name, address.deviceId, record.serialize(),
        )
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        db.query("SELECT 1 FROM signal_session WHERE address = ?", address.key()) { 1 }.isNotEmpty()

    override fun deleteSession(address: SignalProtocolAddress) { db.execute("DELETE FROM signal_session WHERE address = ?", address.key()) }
    override fun deleteAllSessions(name: String) { db.execute("DELETE FROM signal_session WHERE name = ?", name) }
}

private class SqlSenderKeyStore(private val db: SqlDatabase) : SenderKeyStore {
    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        db.execute(
            "INSERT OR REPLACE INTO signal_sender_key (address, distribution_id, record) VALUES (?, ?, ?)",
            sender.key(), distributionId.toString(), record.serialize(),
        )
    }

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        db.query("SELECT record FROM signal_sender_key WHERE address = ? AND distribution_id = ?", sender.key(), distributionId.toString()) {
            SenderKeyRecord(it.blob(0))
        }.firstOrNull()
}

/**
 * États libsignal (identité, sessions, pré-clés, Sender Keys) dans la base chiffrée. Chaque écriture
 * est immédiate : un redémarrage retrouve exactement l'état du Double Ratchet.
 */
class SqlSignalProtocolStore private constructor(
    identity: IdentityKeyStore,
    preKeys: PreKeyStore,
    sessions: SessionStore,
    signedPreKeys: SignedPreKeyStore,
    senderKeys: SenderKeyStore,
    kyberPreKeys: KyberPreKeyStore,
) : SignalProtocolStore,
    IdentityKeyStore by identity,
    PreKeyStore by preKeys,
    SessionStore by sessions,
    SignedPreKeyStore by signedPreKeys,
    SenderKeyStore by senderKeys,
    KyberPreKeyStore by kyberPreKeys {

    constructor(db: SqlDatabase, identityKeyPair: IdentityKeyPair, registrationId: Int) : this(
        SqlIdentityKeyStore(db, identityKeyPair, registrationId),
        SqlPreKeyStore(db), SqlSessionStore(db), SqlSignedPreKeyStore(db), SqlSenderKeyStore(db), SqlKyberPreKeyStore(db),
    )
}
