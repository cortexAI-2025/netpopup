package com.netpopup.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.nio.ByteBuffer
import java.util.Base64

class InvalidBundleException(message: String) : Exception(message)

/**
 * Carte de contact échangée par QR en personne ou annoncée dans une room : la clé
 * d'identité, la pré-clé signée (renouvelée chaque semaine) et, pour PQXDH, une
 * pré-clé Kyber1024 (~1,6 Ko). Pas de pré-clé à usage unique en v0.
 *
 * Format (gros-boutiste) : version(1) ‖ drapeaux(1) ‖ registrationId(4) ‖ IK(33) ‖
 * spkId(4) ‖ SPK(33) ‖ sig(64) ‖ [kyberId(4) ‖ KPK(1569) ‖ sig(64)] ‖ len(1) ‖ nom UTF-8.
 * Chaque signature (XEdDSA, par la clé d'identité) porte sur `Encode(clé)`.
 */
class ContactBundle(
    val name: String,
    val registrationId: Int,
    val identityKey: IdentityKey,
    val signedPreKeyId: Int,
    val signedPreKey: ECPublicKey,
    val signedPreKeySignature: ByteArray,
    val kyberPreKeyId: Int?,
    val kyberPreKey: KEMPublicKey?,
    val kyberPreKeySignature: ByteArray?,
) {
    val hasKyber: Boolean get() = kyberPreKey != null

    fun toPreKeyBundle(): PreKeyBundle = PreKeyBundle(
        registrationId, DEVICE_ID,
        PreKeyBundle.NULL_PRE_KEY_ID, null,
        signedPreKeyId, signedPreKey, signedPreKeySignature,
        identityKey,
        kyberPreKeyId ?: PreKeyBundle.NULL_PRE_KEY_ID, kyberPreKey, kyberPreKeySignature,
    )

    fun encode(): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= MAX_NAME_BYTES) { "name too long" }
        val ik = identityKey.serialize()
        val spk = signedPreKey.serialize()
        val kyber = kyberPreKey?.serialize()
        val size = 2 + 4 + ik.size + 4 + spk.size + SIG_SIZE +
            (if (kyber != null) 4 + kyber.size + SIG_SIZE else 0) + 1 + nameBytes.size
        val buf = ByteBuffer.allocate(size)
        buf.put(VERSION).put(if (kyber != null) FLAG_KYBER else 0)
        buf.putInt(registrationId).put(ik)
        buf.putInt(signedPreKeyId).put(spk).put(signedPreKeySignature)
        if (kyber != null) buf.putInt(kyberPreKeyId!!).put(kyber).put(kyberPreKeySignature!!)
        buf.put(nameBytes.size.toByte()).put(nameBytes)
        return buf.array()
    }

    /** Lien affiché en QR. */
    fun toUri(): String = URI_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(encode())

    companion object {
        const val DEVICE_ID = 1
        const val MAX_NAME_BYTES = 32
        const val URI_PREFIX = "netpopup://contact/"
        private const val VERSION: Byte = 1
        private const val FLAG_KYBER: Byte = 1
        private const val SIG_SIZE = 64
        private const val EC_KEY_SIZE = 33
        private const val KYBER_KEY_SIZE = 1569
        private const val MAX_SIZE = 2 + 4 + EC_KEY_SIZE + 4 + EC_KEY_SIZE + SIG_SIZE +
            4 + KYBER_KEY_SIZE + SIG_SIZE + 1 + MAX_NAME_BYTES

        fun fromUri(uri: String): ContactBundle {
            if (!uri.startsWith(URI_PREFIX)) throw InvalidBundleException("not a contact link")
            val raw = try {
                Base64.getUrlDecoder().decode(uri.removePrefix(URI_PREFIX))
            } catch (e: IllegalArgumentException) {
                throw InvalidBundleException("bad encoding")
            }
            return decode(raw)
        }

        /** Analyse et vérifie les signatures : un bundle renvoyé est authentique vis-à-vis de sa clé d'identité. */
        fun decode(bytes: ByteArray): ContactBundle {
            if (bytes.size < 2 || bytes.size > MAX_SIZE) throw InvalidBundleException("bad size")
            val buf = ByteBuffer.wrap(bytes)
            try {
                if (buf.get() != VERSION) throw InvalidBundleException("unsupported version")
                val flags = buf.get()
                if (flags.toInt() and FLAG_KYBER.toInt().inv() != 0) throw InvalidBundleException("unknown flags")
                val registrationId = buf.getInt()
                val identity = IdentityKey(buf.bytes(EC_KEY_SIZE))
                val spkId = buf.getInt()
                val spkBytes = buf.bytes(EC_KEY_SIZE)
                val spk = ECPublicKey(spkBytes)
                val spkSig = buf.bytes(SIG_SIZE)
                if (!identity.publicKey.verifySignature(spkBytes, spkSig)) {
                    throw InvalidBundleException("bad signed pre-key signature")
                }
                var kyberId: Int? = null
                var kyber: KEMPublicKey? = null
                var kyberSig: ByteArray? = null
                if (flags.toInt() and FLAG_KYBER.toInt() != 0) {
                    kyberId = buf.getInt()
                    val kyberBytes = buf.bytes(KYBER_KEY_SIZE)
                    kyber = KEMPublicKey(kyberBytes)
                    kyberSig = buf.bytes(SIG_SIZE)
                    if (!identity.publicKey.verifySignature(kyberBytes, kyberSig)) {
                        throw InvalidBundleException("bad kyber pre-key signature")
                    }
                }
                val nameLen = buf.get().toInt() and 0xff
                if (nameLen > MAX_NAME_BYTES) throw InvalidBundleException("name too long")
                val name = String(buf.bytes(nameLen), Charsets.UTF_8)
                if (buf.hasRemaining()) throw InvalidBundleException("trailing bytes")
                return ContactBundle(name, registrationId, identity, spkId, spk, spkSig, kyberId, kyber, kyberSig)
            } catch (e: java.nio.BufferUnderflowException) {
                throw InvalidBundleException("truncated")
            } catch (e: InvalidKeyException) {
                throw InvalidBundleException("bad key")
            } catch (e: IllegalArgumentException) {
                throw InvalidBundleException("bad key")
            }
        }

        private fun ByteBuffer.bytes(n: Int): ByteArray = ByteArray(n).also { get(it) }
    }
}
