package com.netpopup.crypto.room

import com.netpopup.crypto.ContactBook
import com.netpopup.crypto.ContactBundle
import com.netpopup.crypto.InvalidBundleException
import com.netpopup.crypto.LocalIdentity
import com.netpopup.crypto.OuterLayer
import com.netpopup.crypto.PairwiseSession
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.state.SignalProtocolStore
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Rooms privées : secret d'époque de 256 bits, Sender Keys libsignal pour les messages,
 * sessions 1:1 pour distribuer `ROOM_KEY` et les Sender Keys.
 *
 * Modèle de confiance :
 *  - le **code d'invitation** ne permet que de déposer une demande (`ROOM_JOIN`) ;
 *  - l'accès vient d'un `ROOM_KEY` reçu par session 1:1, dont l'en-tête est **signé par le créateur**
 *    (fixé par l'invitation) : un tiers qui connaît le code ne peut pas fournir un faux secret ;
 *  - en mode `CREATOR_ONLY`, l'expéditeur du `ROOM_KEY` doit en plus être le créateur ;
 *  - seul le créateur retire un membre : il ouvre une nouvelle époque (nouveau secret, nouvelles
 *    Sender Keys) et la redistribue aux membres restants.
 *
 * Les listes de membres ne sont pas signées : un membre malveillant peut y ajouter une clé,
 * mais il lit déjà tout ce que lit un membre.
 */
class RoomService(
    private val store: SignalProtocolStore,
    private val self: LocalIdentity,
    private val pairwise: PairwiseSession,
    private val contacts: ContactBook,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val random = SecureRandom()

    /**
     * Message de contrôle en **clair** pour [recipient] : l'appelant le chiffre par la session 1:1
     * (et y joint, s'il le veut, un jeton d'acquittement) avant de l'envelopper.
     */
    class Outgoing(val recipient: IdentityKey, val payload: ByteArray)

    class Distribution(val outgoing: List<Outgoing>, val unreachable: List<IdentityKey>)

    class JoinRequest(val roomId: ByteArray, val bundle: ContactBundle)

    // ── Création et invitation ───────────────────────────────────────────────

    fun create(name: String, policy: AdmissionPolicy): Pair<RoomState, RoomInvite> {
        val roomId = ByteArray(16).also(random::nextBytes)
        val code = ByteArray(16).also(random::nextBytes)
        val secret = ByteArray(32).also(random::nextBytes)
        val invite = RoomInvite(roomId, code, self.publicKey, policy, name)
        invite.encode() // valide la longueur du nom
        val header = RoomHeader(roomId, 0, policy, code, RoomKeys.commitment(roomId, 0, secret))
        val room = RoomState(roomId, name, self.publicKey, policy, code, 0, secret, header, sign(header), listOf(self.publicKey))
        return room to invite
    }

    // ── Demande d'accès ──────────────────────────────────────────────────────

    /** Payload de `ROOM_JOIN`, chiffré avec les clés dérivées du code ; [aad] est l'AAD de l'enveloppe. */
    fun sealJoin(invite: RoomInvite, aad: ByteArray, myBundle: ContactBundle): ByteArray {
        val bundle = myBundle.encode()
        val body = ByteBuffer.allocate(1 + 16 + 2 + bundle.size)
            .put(TYPE_JOIN).put(invite.roomId).putShort(bundle.size.toShort()).put(bundle).array()
        return OuterLayer.seal(invite.joinKeys.envKey, aad, body)
    }

    /** Ouvre une demande reçue sur la file de la room ; null si elle n'est pas pour cette room ou est invalide. */
    fun openJoin(room: RoomState, aad: ByteArray, payload: ByteArray): JoinRequest? {
        val body = OuterLayer.open(RoomKeys.join(room.inviteCode, room.roomId).envKey, aad, payload) ?: return null
        return try {
            val b = ByteBuffer.wrap(body)
            if (b.get() != TYPE_JOIN) return null
            val roomId = ByteArray(16).also { b.get(it) }
            if (!roomId.contentEquals(room.roomId)) return null
            val len = b.getShort().toInt() and 0xffff
            if (len != b.remaining()) return null
            JoinRequest(roomId, ContactBundle.decode(ByteArray(len).also { b.get(it) }))
        } catch (e: Exception) {
            if (e is InvalidBundleException || e is java.nio.BufferUnderflowException) null else throw e
        }
    }

    /** Accepte une demande : ajoute le demandeur et produit le `ROOM_KEY` pour lui. */
    fun accept(room: RoomState, request: JoinRequest): Outgoing {
        if (room.policy == AdmissionPolicy.CREATOR_ONLY && self.publicKey != room.creator) {
            throw RoomException("only the creator can accept requests in this room")
        }
        if (!room.isMember(request.bundle.identityKey) && room.members.size >= RoomState.MAX_MEMBERS) {
            throw RoomException("room is full")
        }
        contacts.addFromAnnounce(request.bundle)
        if (!pairwise.hasSession(request.bundle.identityKey)) pairwise.start(request.bundle)
        room.addMember(request.bundle.identityKey)
        return roomKeyFor(room, request.bundle.identityKey)
    }

    /**
     * Après une admission, informe les autres membres (même époque, liste à jour) pour que tous
     * apprennent le nouveau venu et lui envoient leur Sender Key.
     */
    fun membershipUpdate(room: RoomState, except: IdentityKey? = null, include: (IdentityKey) -> Boolean = { true }): Distribution =
        sendToMembers(room.members.filter { it != self.publicKey && it != except && include(it) }) { roomKeyFor(room, it) }

    /** Côté demandeur : installe la room depuis un `ROOM_KEY` reçu de [sender], après vérification complète. */
    fun joinFromRoomKey(invite: RoomInvite, sender: IdentityKey, plaintext: ByteArray): RoomState {
        val msg = parseRoomKey(plaintext)
        checkAuthentic(msg, invite.roomId, invite.creator, invite.policy, invite.code, sender)
        val members = msg.members.toMutableList()
        if (members.none { it == self.publicKey }) members.add(self.publicKey)
        return RoomState(
            invite.roomId, invite.name, invite.creator, invite.policy, invite.code,
            msg.header.epoch, msg.secret, msg.header, msg.signature, members,
        )
    }

    // ── Rotation ─────────────────────────────────────────────────────────────

    /** Côté membre : applique un `ROOM_KEY` de nouvelle époque (ou fusionne les membres de la même époque). */
    fun onRoomKey(room: RoomState, sender: IdentityKey, plaintext: ByteArray) {
        val msg = parseRoomKey(plaintext)
        checkAuthentic(msg, room.roomId, room.creator, room.policy, room.inviteCode, sender)
        when {
            msg.header.epoch < room.epoch -> throw RoomException("stale epoch")
            msg.header.epoch == room.epoch -> {
                if (!room.isMember(sender)) throw RoomException("sender is not a member")
                if (!msg.secret.contentEquals(room.secret)) throw RoomException("conflicting secret for this epoch")
                msg.members.forEach(room::addMember)
            }
            else -> {
                if (sender != room.creator) throw RoomException("only the creator can open a new epoch")
                room.retired.add(RetiredEpoch(room.epoch, room.secret, nowMillis()))
                room.epoch = msg.header.epoch
                room.secret = msg.secret
                room.header = msg.header
                room.headerSignature = msg.signature
                room.setMembers(msg.members.toMutableList().also { if (it.none { m -> m == self.publicKey }) it.add(self.publicKey) })
                room.startRotation(room.members.filter { it != self.publicKey })
            }
        }
    }

    /** Retire [member] (créateur seulement) : nouvelle époque, redistribuée aux membres restants. */
    fun remove(room: RoomState, member: IdentityKey): Distribution {
        if (self.publicKey != room.creator) throw RoomException("only the creator can remove members")
        if (member == room.creator) throw RoomException("the creator cannot be removed")
        if (!room.isMember(member)) throw RoomException("not a member")
        val newEpoch = room.epoch + 1
        val secret = ByteArray(32).also(random::nextBytes)
        room.retired.add(RetiredEpoch(room.epoch, room.secret, nowMillis()))
        room.epoch = newEpoch
        room.secret = secret
        room.header = RoomHeader(room.roomId, newEpoch, room.policy, room.inviteCode, RoomKeys.commitment(room.roomId, newEpoch, secret))
        room.headerSignature = sign(room.header)
        room.setMembers(room.members.filter { it != member })
        val others = room.members.filter { it != self.publicKey }
        room.startRotation(others)
        return sendToMembers(others) { roomKeyFor(room, it) }
    }

    // ── Sender Keys ──────────────────────────────────────────────────────────

    /** Crée la Sender Key de l'époque courante et la prépare pour chaque membre joignable. */
    fun senderKeyDistribution(room: RoomState, include: (IdentityKey) -> Boolean = { true }): Distribution {
        val skdm = GroupSessionBuilder(store).create(PairwiseSession.addressOf(self.publicKey), RoomKeys.distributionId(room.roomId, room.epoch))
        val raw = skdm.serialize()
        val body = ByteBuffer.allocate(1 + 16 + 4 + 2 + raw.size)
            .put(TYPE_SENDER_KEY).put(room.roomId).putInt(room.epoch).putShort(raw.size.toShort()).put(raw).array()
        return sendToMembers(room.members.filter { it != self.publicKey && include(it) }) { Outgoing(it, body) }
    }

    fun onSenderKey(room: RoomState, sender: IdentityKey, plaintext: ByteArray) {
        if (!room.isMember(sender)) throw RoomException("sender is not a member")
        try {
            val b = ByteBuffer.wrap(plaintext)
            if (b.get() != TYPE_SENDER_KEY) throw RoomException("not a sender key")
            val roomId = ByteArray(16).also { b.get(it) }
            val epoch = b.getInt()
            val len = b.getShort().toInt() and 0xffff
            if (!roomId.contentEquals(room.roomId)) throw RoomException("wrong room")
            if (epoch != room.epoch) throw RoomException("sender key for another epoch")
            if (len != b.remaining()) throw RoomException("bad length")
            val skdm = SenderKeyDistributionMessage(ByteArray(len).also { b.get(it) })
            if (skdm.distributionId != RoomKeys.distributionId(room.roomId, room.epoch)) throw RoomException("wrong distribution id")
            GroupSessionBuilder(store).process(PairwiseSession.addressOf(sender), skdm)
            room.markRotated(sender)
        } catch (e: RoomException) {
            throw e
        } catch (e: Exception) {
            throw RoomException("invalid sender key: ${e.message}")
        }
    }

    // ── Messages et annonces ─────────────────────────────────────────────────

    /** Chiffre un message de room : Sender Key à l'intérieur, secret d'époque à l'extérieur. */
    fun sealMessage(room: RoomState, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val me = PairwiseSession.addressOf(self.publicKey)
        val inner = GroupCipher(store, me).encrypt(RoomKeys.distributionId(room.roomId, room.epoch), plaintext).serialize()
        return OuterLayer.seal(room.keys.envKey, aad, senderId(self.publicKey) + inner)
    }

    class Received(val sender: IdentityKey, val plaintext: ByteArray)

    /** Ouvre un message de room avec les clés d'époque acceptées ; null s'il n'est pas lisible ou si l'expéditeur n'est plus membre. */
    fun openMessage(room: RoomState, aad: ByteArray, payload: ByteArray): Received? {
        for (keys in room.acceptedKeys(nowMillis())) {
            val opened = OuterLayer.open(keys.envKey, aad, payload) ?: continue
            if (opened.size <= 16) return null
            val id = opened.copyOfRange(0, 16)
            val sender = room.members.firstOrNull { senderId(it).contentEquals(id) } ?: return null
            return try {
                Received(sender, GroupCipher(store, PairwiseSession.addressOf(sender)).decrypt(opened.copyOfRange(16, opened.size)))
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    /** `ANNOUNCE` de room : publie son bundle de contact pour que les membres puissent ouvrir des sessions 1:1. */
    fun sealAnnounce(room: RoomState, aad: ByteArray, myBundle: ContactBundle): ByteArray =
        OuterLayer.seal(room.keys.envKey, aad, myBundle.encode())

    /** Enregistre le bundle d'un membre annoncé ; ignore tout bundle d'un non-membre. */
    fun openAnnounce(room: RoomState, aad: ByteArray, payload: ByteArray): ContactBundle? {
        for (keys in room.acceptedKeys(nowMillis())) {
            val opened = OuterLayer.open(keys.envKey, aad, payload) ?: continue
            val bundle = try { ContactBundle.decode(opened) } catch (e: InvalidBundleException) { return null }
            if (!room.isMember(bundle.identityKey)) return null
            contacts.addFromAnnounce(bundle)
            return bundle
        }
        return null
    }

    fun typeOf(plaintext: ByteArray): Byte? = plaintext.firstOrNull()

    // ── Interne ──────────────────────────────────────────────────────────────

    private class RoomKeyMessage(val header: RoomHeader, val signature: ByteArray, val secret: ByteArray, val members: List<IdentityKey>)

    private fun roomKeyFor(room: RoomState, recipient: IdentityKey): Outgoing {
        val members = room.members
        val body = ByteBuffer.allocate(1 + RoomHeader.SIZE + 64 + 32 + 1 + 33 * members.size)
            .put(TYPE_ROOM_KEY).put(room.header.encode()).put(room.headerSignature).put(room.secret)
            .put(members.size.toByte()).also { b -> members.forEach { b.put(it.serialize()) } }.array()
        return Outgoing(recipient, body)
    }

    private fun parseRoomKey(plaintext: ByteArray): RoomKeyMessage = try {
        val b = ByteBuffer.wrap(plaintext)
        if (b.get() != TYPE_ROOM_KEY) throw RoomException("not a room key")
        val header = RoomHeader.decode(b)
        val sig = ByteArray(64).also { b.get(it) }
        val secret = ByteArray(32).also { b.get(it) }
        val n = b.get().toInt() and 0xff
        if (n > RoomState.MAX_MEMBERS) throw RoomException("too many members")
        val members = List(n) { IdentityKey(ByteArray(33).also { b.get(it) }) }
        if (b.hasRemaining()) throw RoomException("trailing bytes")
        RoomKeyMessage(header, sig, secret, members)
    } catch (e: RoomException) {
        throw e
    } catch (e: Exception) {
        throw RoomException("malformed room key")
    }

    private fun checkAuthentic(
        msg: RoomKeyMessage, roomId: ByteArray, creator: IdentityKey, policy: AdmissionPolicy,
        inviteCode: ByteArray, sender: IdentityKey,
    ) {
        val h = msg.header
        if (!h.roomId.contentEquals(roomId)) throw RoomException("wrong room")
        if (h.policy != policy) throw RoomException("policy mismatch")
        if (!h.inviteCode.contentEquals(inviteCode)) throw RoomException("invite code mismatch")
        if (!creator.publicKey.verifySignature(h.signedBytes(), msg.signature)) throw RoomException("header not signed by the creator")
        if (!java.security.MessageDigest.isEqual(h.commitment, RoomKeys.commitment(roomId, h.epoch, msg.secret))) {
            throw RoomException("secret does not match the signed commitment")
        }
        if (policy == AdmissionPolicy.CREATOR_ONLY && sender != creator) throw RoomException("room key not sent by the creator")
    }

    private fun sign(header: RoomHeader): ByteArray = self.keyPair.privateKey.calculateSignature(header.signedBytes())

    private fun senderId(id: IdentityKey): ByteArray =
        PairwiseSession.addressOf(id).name.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun sendToMembers(targets: List<IdentityKey>, build: (IdentityKey) -> Outgoing): Distribution {
        val out = ArrayList<Outgoing>()
        val unreachable = ArrayList<IdentityKey>()
        for (t in targets) {
            if (!pairwise.hasSession(t)) {
                val bundle = contacts.find(t)?.bundle
                if (bundle == null) { unreachable.add(t); continue }
                pairwise.start(bundle)
            }
            out.add(build(t))
        }
        return Distribution(out, unreachable)
    }

    companion object {
        const val TYPE_JOIN: Byte = 1
        const val TYPE_ROOM_KEY: Byte = 2
        const val TYPE_SENDER_KEY: Byte = 3
    }
}
