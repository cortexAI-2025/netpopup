package com.netpopup.crypto.room

import com.netpopup.crypto.ChannelKeys
import com.netpopup.crypto.Hkdf
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class RoomException(message: String) : Exception(message)

/** Qui peut accepter une demande d'accès. Seul le créateur peut retirer un membre, quelle que soit la politique. */
enum class AdmissionPolicy(val code: Byte) {
    ANY_MEMBER(0),
    CREATOR_ONLY(1);

    companion object {
        fun of(code: Byte): AdmissionPolicy = values().firstOrNull { it.code == code }
            ?: throw RoomException("unknown admission policy")
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

internal object RoomKeys {
    private const val JOIN_SALT = "netpopup/v0/join"

    /**
     * Clés de la file de demandes d'accès, dérivées du code d'invitation (128 bits). Le code
     * ne donne **aucun** accès aux messages : seulement le droit de déposer une demande.
     */
    fun join(code: ByteArray, roomId: ByteArray): ChannelKeys {
        val secret = Hkdf.derive(code, JOIN_SALT.toByteArray(), roomId, 32)
        return ChannelKeys.derive(secret, "join/${roomId.hex()}")
    }

    fun epoch(secret: ByteArray, roomId: ByteArray, epoch: Int): ChannelKeys =
        ChannelKeys.derive(secret, "room/${roomId.hex()}/$epoch")

    fun distributionId(roomId: ByteArray, epoch: Int): UUID {
        val d = MessageDigest.getInstance("SHA-256")
            .digest("netpopup/v0/distribution".toByteArray() + roomId + ByteBuffer.allocate(4).putInt(epoch).array())
        val b = ByteBuffer.wrap(d)
        return UUID(b.long, b.long)
    }

    fun commitment(roomId: ByteArray, epoch: Int, secret: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest("netpopup/v0/room-commit".toByteArray() + roomId + ByteBuffer.allocate(4).putInt(epoch).array() + secret)
}

/**
 * Invitation partagée par QR ou lien. Elle fixe, hors du mesh, le créateur et la politique :
 * celui qui reçoit une `ROOM_KEY` peut ainsi vérifier qu'elle vient bien de cette room.
 */
class RoomInvite(
    val roomId: ByteArray,
    val code: ByteArray,
    val creator: IdentityKey,
    val policy: AdmissionPolicy,
    val name: String,
) {
    val joinKeys: ChannelKeys get() = RoomKeys.join(code, roomId)

    fun encode(): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        require(n.size <= MAX_NAME_BYTES) { "room name too long" }
        return ByteBuffer.allocate(2 + 16 + 16 + 33 + 1 + n.size)
            .put(VERSION).put(policy.code).put(roomId).put(code).put(creator.serialize())
            .put(n.size.toByte()).put(n).array()
    }

    fun toUri(): String = URI_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(encode())

    companion object {
        const val MAX_NAME_BYTES = 32
        const val URI_PREFIX = "netpopup://room/"
        private const val VERSION: Byte = 1

        fun fromUri(uri: String): RoomInvite {
            if (!uri.startsWith(URI_PREFIX)) throw RoomException("not a room link")
            val raw = try {
                Base64.getUrlDecoder().decode(uri.removePrefix(URI_PREFIX))
            } catch (e: IllegalArgumentException) {
                throw RoomException("bad encoding")
            }
            return decode(raw)
        }

        fun decode(bytes: ByteArray): RoomInvite {
            try {
                val b = ByteBuffer.wrap(bytes)
                if (b.get() != VERSION) throw RoomException("unsupported invite version")
                val policy = AdmissionPolicy.of(b.get())
                val roomId = ByteArray(16).also { b.get(it) }
                val code = ByteArray(16).also { b.get(it) }
                val creator = IdentityKey(ByteArray(33).also { b.get(it) })
                val len = b.get().toInt() and 0xff
                if (len > MAX_NAME_BYTES) throw RoomException("room name too long")
                val name = String(ByteArray(len).also { b.get(it) }, Charsets.UTF_8)
                if (b.hasRemaining()) throw RoomException("trailing bytes")
                return RoomInvite(roomId, code, creator, policy, name)
            } catch (e: java.nio.BufferUnderflowException) {
                throw RoomException("truncated invite")
            } catch (e: InvalidKeyException) {
                throw RoomException("bad key")
            } catch (e: IllegalArgumentException) {
                throw RoomException("bad key")
            }
        }
    }
}

/** En-tête d'époque, signé par le créateur : lie la room, l'époque, la politique et l'empreinte du secret. */
class RoomHeader(
    val roomId: ByteArray,
    val epoch: Int,
    val policy: AdmissionPolicy,
    val inviteCode: ByteArray,
    val commitment: ByteArray,
) {
    fun encode(): ByteArray = ByteBuffer.allocate(SIZE)
        .put(roomId).putInt(epoch).put(policy.code).put(inviteCode).put(commitment).array()

    fun signedBytes(): ByteArray = "netpopup/v0/room-header".toByteArray() + encode()

    companion object {
        const val SIZE = 16 + 4 + 1 + 16 + 32

        fun decode(b: ByteBuffer): RoomHeader {
            val roomId = ByteArray(16).also { b.get(it) }
            val epoch = b.getInt()
            val policy = AdmissionPolicy.of(b.get())
            val code = ByteArray(16).also { b.get(it) }
            val commitment = ByteArray(32).also { b.get(it) }
            return RoomHeader(roomId, epoch, policy, code, commitment)
        }
    }
}

internal class RetiredEpoch(val epoch: Int, val secret: ByteArray, val retiredAtMillis: Long)

/** État local d'une room privée. La persistance chiffrée viendra avec mesh-13. */
class RoomState internal constructor(
    val roomId: ByteArray,
    val name: String,
    val creator: IdentityKey,
    val policy: AdmissionPolicy,
    val inviteCode: ByteArray,
    epoch: Int,
    secret: ByteArray,
    header: RoomHeader,
    headerSignature: ByteArray,
    members: List<IdentityKey>,
) {
    var epoch: Int = epoch; internal set
    internal var secret: ByteArray = secret
    internal var header: RoomHeader = header
    internal var headerSignature: ByteArray = headerSignature
    private val memberList = ArrayList(members)
    internal val retired = ArrayList<RetiredEpoch>()
    private val pendingRotation = LinkedHashSet<String>()

    val members: List<IdentityKey> get() = memberList.toList()
    val keys: ChannelKeys get() = RoomKeys.epoch(secret, roomId, epoch)

    /** Vrai tant que des membres n'ont pas encore émis leurs nouvelles Sender Keys après un retrait. */
    val rotationInProgress: Boolean get() = pendingRotation.isNotEmpty()

    fun isMember(id: IdentityKey) = memberList.any { it == id }

    internal fun addMember(id: IdentityKey) {
        if (!isMember(id)) memberList.add(id)
    }

    internal fun setMembers(list: List<IdentityKey>) {
        memberList.clear(); memberList.addAll(list)
    }

    internal fun startRotation(waitingFor: Collection<IdentityKey>) {
        pendingRotation.clear()
        waitingFor.forEach { pendingRotation.add(it.serialize().hex()) }
    }

    internal fun markRotated(id: IdentityKey) {
        pendingRotation.remove(id.serialize().hex())
    }

    /** Clés de l'époque courante puis des précédentes encore dans la fenêtre de grâce (messages en vol). */
    fun acceptedKeys(nowMillis: Long): List<ChannelKeys> =
        listOf(keys) + retired.filter { nowMillis - it.retiredAtMillis < GRACE_MILLIS }
            .sortedByDescending { it.epoch }.map { RoomKeys.epoch(it.secret, roomId, it.epoch) }

    companion object {
        const val MAX_MEMBERS = 32

        /** 24 h de durée de vie maximale + 2 h de tolérance d'horloge. */
        const val GRACE_MILLIS = 26L * 3600 * 1000
    }
}
