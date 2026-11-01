package com.netpopup.v2.rooms

import java.nio.ByteBuffer
import java.util.Base64

/**
 * Enveloppe d'un message de salon, portée par un paquet de diffusion du mesh (texte `NPR1:` + Base64).
 *
 * Format binaire : version(1) ‖ étiquette du salon(8) ‖ nonce(12) ‖ AES-GCM(clair). Le clair contient l'identifiant du message
 * (16 octets), l'horodatage (8), le pseudo (1 + n) puis le texte. Aucun nom de salon ni mot de passe ne circule.
 */
object RoomEnvelope {
    const val PREFIX = "NPR1:"
    private const val VERSION: Byte = 1
    const val MAX_TEXT_BYTES = 400
    private const val MAX_NICK_BYTES = 64

    class Plain(val id: ByteArray, val timestampMs: Long, val nickname: String, val text: String)

    fun looksLikeRoomPacket(payload: ByteArray): Boolean =
        payload.size > PREFIX.length && String(payload, 0, PREFIX.length, Charsets.US_ASCII) == PREFIX

    fun build(master: ByteArray, nickname: String, text: String, timestampMs: Long, id: ByteArray = RoomCrypto.newMessageId()): String {
        val nick = nickname.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_NICK_BYTES) it.copyOf(MAX_NICK_BYTES) else it }
        val body = text.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_TEXT_BYTES) it.copyOf(MAX_TEXT_BYTES) else it }
        val plain = ByteBuffer.allocate(16 + 8 + 1 + nick.size + body.size)
            .put(id).putLong(timestampMs).put(nick.size.toByte()).put(nick).put(body).array()
        val sealed = RoomCrypto.seal(master, plain)
        val raw = ByteBuffer.allocate(1 + RoomCrypto.TAG_BYTES + sealed.size)
            .put(VERSION).put(RoomCrypto.tag(master)).put(sealed).array()
        return PREFIX + Base64.getEncoder().withoutPadding().encodeToString(raw)
    }

    /** Étiquette du salon visé, ou `null` si le paquet n'est pas une enveloppe valide. */
    fun tagOf(payload: ByteArray): ByteArray? = decodeRaw(payload)?.copyOfRange(1, 1 + RoomCrypto.TAG_BYTES)

    fun open(master: ByteArray, payload: ByteArray): Plain? {
        val raw = decodeRaw(payload) ?: return null
        val tag = raw.copyOfRange(1, 1 + RoomCrypto.TAG_BYTES)
        if (!tag.contentEquals(RoomCrypto.tag(master))) return null
        val plain = RoomCrypto.open(master, raw.copyOfRange(1 + RoomCrypto.TAG_BYTES, raw.size)) ?: return null
        if (plain.size < 16 + 8 + 1) return null
        return try {
            val b = ByteBuffer.wrap(plain)
            val id = ByteArray(16).also(b::get)
            val ts = b.long
            val nickLen = b.get().toInt() and 0xFF
            if (nickLen > b.remaining()) return null
            val nick = ByteArray(nickLen).also(b::get)
            val text = ByteArray(b.remaining()).also(b::get)
            Plain(id, ts, String(nick, Charsets.UTF_8), String(text, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeRaw(payload: ByteArray): ByteArray? {
        if (!looksLikeRoomPacket(payload)) return null
        return try {
            val raw = Base64.getDecoder().decode(String(payload, PREFIX.length, payload.size - PREFIX.length, Charsets.US_ASCII))
            if (raw.size < 1 + RoomCrypto.TAG_BYTES + 12 + 16 || raw[0] != VERSION) null else raw
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
