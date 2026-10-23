package com.netpopup.engine

import com.netpopup.crypto.ChannelKeys
import com.netpopup.crypto.Hkdf
import com.netpopup.crypto.LocalIdentity
import com.netpopup.crypto.OuterLayer
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyException
import java.nio.ByteBuffer

/**
 * Room locale publique : étiquette et clé connues de tous (le format d'enveloppe reste unique), donc
 * aucune confidentialité. Chaque message est signé par la clé d'identité de l'émetteur, qui prouve
 * son authenticité : un relais ne peut ni l'altérer ni le faire passer pour un autre.
 */
internal object PublicRoom {
    val keys: ChannelKeys = ChannelKeys.derive(
        Hkdf.derive("netpopup/v0/public-room".toByteArray(), null, ByteArray(0), 32), "public"
    )

    class Message(val sender: IdentityKey, val name: String, val text: String)

    private const val VERSION: Byte = 1

    fun seal(identity: LocalIdentity, name: String, text: String, aad: ByteArray): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val t = text.toByteArray(Charsets.UTF_8)
        val signature = identity.keyPair.privateKey.calculateSignature(aad + n + t)
        val body = ByteBuffer.allocate(2 + n.size + 33 + 64 + t.size)
            .put(VERSION).put(n.size.toByte()).put(n).put(identity.publicKey.serialize()).put(signature).put(t).array()
        return OuterLayer.seal(keys.envKey, aad, body)
    }

    /** Null si le message est illisible ou si sa signature est fausse. */
    fun open(aad: ByteArray, payload: ByteArray): Message? {
        val body = OuterLayer.open(keys.envKey, aad, payload) ?: return null
        return try {
            val b = ByteBuffer.wrap(body)
            if (b.get() != VERSION) return null
            val nameLen = b.get().toInt() and 0xff
            if (nameLen > MAX_NAME_BYTES) return null
            val n = ByteArray(nameLen).also { b.get(it) }
            val ik = IdentityKey(ByteArray(33).also { b.get(it) })
            val sig = ByteArray(64).also { b.get(it) }
            val t = ByteArray(b.remaining()).also { b.get(it) }
            if (!ik.publicKey.verifySignature(aad + n + t, sig)) return null
            Message(ik, String(n, Charsets.UTF_8), String(t, Charsets.UTF_8))
        } catch (e: java.nio.BufferUnderflowException) {
            null
        } catch (e: InvalidKeyException) {
            null
        }
    }

    const val MAX_NAME_BYTES = 32
}

/**
 * Boîte d'introduction d'un contact : quand A scanne le QR de B, B ne sait pas encore qui est A et ne
 * peut donc pas calculer leur étiquette 1:1. A dépose son bundle sur l'étiquette de la boîte de B,
 * que n'importe quel porteur du lien de B peut calculer. B y apprend A comme contact « annoncé »
 * (jamais « vérifié » : B n'a pas vu A en personne).
 */
internal object Inbox {
    fun keys(owner: IdentityKey): ChannelKeys = ChannelKeys.derive(
        Hkdf.derive(owner.serialize(), "netpopup/v0/inbox".toByteArray(), ByteArray(0), 32), "inbox"
    )
}
