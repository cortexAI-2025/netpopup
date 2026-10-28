package com.netpopup.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.SignalProtocolStore
import java.security.MessageDigest

/** Message 1:1 illisible, falsifié, rejoué ou dont la session manque. */
class PairwiseDecryptionException(cause: Throwable) : Exception(cause.message, cause)

/**
 * Session 1:1 : X3DH (ou PQXDH si le bundle porte une pré-clé Kyber) puis Double Ratchet,
 * par libsignal. Le résultat de `encrypt` est le payload interne, à envelopper ensuite
 * par `OuterLayer`. Les messages dans le désordre ou très en retard se déchiffrent tant
 * que les clés de message ignorées sont conservées par libsignal.
 */
class PairwiseSession(private val store: SignalProtocolStore) {

    /** Ouvre une session vers le propriétaire de [bundle], déjà authentifié (QR ou annonce). */
    fun start(bundle: ContactBundle) {
        SessionBuilder(store, addressOf(bundle.identityKey)).process(bundle.toPreKeyBundle())
    }

    fun hasSession(remote: IdentityKey): Boolean =
        store.containsSession(addressOf(remote))

    /** `type(1) ‖ message libsignal` : 3 = premier message (porte les infos d'échange), 2 = suivants. */
    fun encrypt(remote: IdentityKey, plaintext: ByteArray): ByteArray {
        val message = SessionCipher(store, addressOf(remote)).encrypt(plaintext)
        return byteArrayOf(message.type.toByte()) + message.serialize()
    }

    /**
     * Déchiffre un message reçu. Pour un premier message, [knownSender] peut être nul :
     * l'identité de l'expéditeur est lue dans le message lui-même.
     */
    fun decrypt(data: ByteArray, knownSender: IdentityKey? = null): DecryptResult {
        if (data.size < 2) throw PairwiseDecryptionException(IllegalArgumentException("too short"))
        val body = data.copyOfRange(1, data.size)
        try {
            return when (data[0].toInt()) {
                CiphertextMessage.PREKEY_TYPE -> {
                    val message = PreKeySignalMessage(body)
                    val sender = message.identityKey
                    DecryptResult(sender, SessionCipher(store, addressOf(sender)).decrypt(message))
                }
                CiphertextMessage.WHISPER_TYPE -> {
                    val sender = knownSender ?: throw IllegalArgumentException("sender required")
                    DecryptResult(sender, SessionCipher(store, addressOf(sender)).decrypt(SignalMessage(body)))
                }
                else -> throw IllegalArgumentException("unknown message type")
            }
        } catch (e: Exception) {
            throw PairwiseDecryptionException(e)
        }
    }

    class DecryptResult(val sender: IdentityKey, val plaintext: ByteArray)

    companion object {
        /** Adresse libsignal dérivée de la clé d'identité (pas de nom de compte : pas de serveur). */
        fun addressOf(identity: IdentityKey): SignalProtocolAddress {
            val digest = MessageDigest.getInstance("SHA-256").digest(identity.serialize())
            return SignalProtocolAddress(digest.take(16).joinToString("") { "%02x".format(it) }, ContactBundle.DEVICE_ID)
        }

        /**
         * Clés de conversation 1:1 : `S = DH(IK_A, IK_B)` puis HKDF. Symétrique, donc les deux
         * côtés dérivent les mêmes `pairTagKey` / `pairEnvKey` sans rien échanger.
         * Limite : `S` est statique, la couche extérieure n'a donc pas de confidentialité
         * persistante ; celle-ci vient du Double Ratchet de la couche intérieure.
         */
        fun pairKeys(local: IdentityKeyPair, remote: IdentityKey): ChannelKeys {
            val secret = local.privateKey.calculateAgreement(remote.publicKey)
            val ids = listOf(local.publicKey.serialize(), remote.serialize())
                .sortedWith(UnsignedBytes)
            val scope = "pair/" + ids.joinToString("/") { it.joinToString("") { b -> "%02x".format(b) } }
            return ChannelKeys.derive(secret, scope)
        }

        /** Numéro de sécurité (60 chiffres) à comparer en personne ; identique des deux côtés. */
        fun safetyNumber(local: IdentityKey, remote: IdentityKey): String {
            val fp = NumericFingerprintGenerator(5200)
                .createFor(2, local.serialize(), local, remote.serialize(), remote)
            return fp.displayableFingerprint.displayText.chunked(5).joinToString(" ")
        }
    }
}
