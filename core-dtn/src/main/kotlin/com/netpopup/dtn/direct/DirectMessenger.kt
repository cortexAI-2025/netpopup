package com.netpopup.dtn.direct

import com.netpopup.crypto.LocalIdentity
import com.netpopup.crypto.OuterLayer
import com.netpopup.crypto.PairwiseDecryptionException
import com.netpopup.crypto.PairwiseSession
import com.netpopup.dtn.chat.DeliveryState
import com.netpopup.dtn.chat.MessageId
import com.netpopup.dtn.chat.MessageRules
import com.netpopup.dtn.envelope.AckTokens
import com.netpopup.dtn.envelope.DestTags
import com.netpopup.dtn.envelope.Envelope
import com.netpopup.dtn.envelope.EnvelopeId
import com.netpopup.dtn.envelope.EnvelopeKind
import com.netpopup.dtn.store.EnvelopeStore
import com.netpopup.dtn.store.Origin
import org.signal.libsignal.protocol.IdentityKey
import java.nio.ByteBuffer
import java.util.UUID

sealed interface DirectEvent {
    /** Message déchiffré, adressé à cet appareil ; l'`ACK` correspondant est déjà dans le store. */
    data class Received(val envelopeId: EnvelopeId, val body: String, val sentAtMillis: Long) : DirectEvent

    /** Un message envoyé par cet appareil vient d'être acquitté par son destinataire. */
    data class Acknowledged(val messageId: MessageId) : DirectEvent

    data class Rejected(val reason: Reason) : DirectEvent

    enum class Reason { NOT_FOR_THIS_CONVERSATION, OWN_ENVELOPE, UNDECRYPTABLE, MALFORMED, UNKNOWN_ACK_TOKEN }
}

/**
 * Conversation 1:1 avec un contact : émission des `DATA`, accusé de réception de bout en bout.
 *
 *  - **Envoi** : un `ackToken` aléatoire de 16 octets part dans le payload chiffré ; son hachage
 *    H(`ackToken`) est dans le champ `ack` de l'en-tête, visible des relais.
 *  - **Réception** : après déchiffrement, le destinataire publie une enveloppe `ACK` (6 h) qui porte
 *    le jeton en clair dans son champ `ack`, et retire la `DATA` de son store.
 *  - **Relais** : `EnvelopeStore` supprime toute `DATA` dont le champ `ack` vaut H(jeton) et relaie
 *    l'`ACK`. Sans le jeton, personne ne peut forger un `ACK` valable.
 *  - **Émetteur** : le jeton retrouvé passe le message à [DeliveryState.ACKNOWLEDGED].
 *
 * Réservé au 1:1. Les rooms n'ont ni `ACK` ni état acquitté : leurs `DATA` ont un champ `ack` nul,
 * que le store n'associe jamais à un acquittement.
 *
 * L'état des envois en attente est en mémoire ; sa persistance viendra avec mesh-13.
 */
class DirectMessenger(
    private val local: LocalIdentity,
    val remote: IdentityKey,
    private val pairwise: PairwiseSession,
    private val store: EnvelopeStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val keys = PairwiseSession.pairKeys(local.keyPair, remote)

    private class Pending(val messageId: MessageId, val envelopeId: EnvelopeId, var state: DeliveryState)

    private val pendingByTokenHash = HashMap<String, Pending>()
    private val ownEnvelopeIds = HashSet<EnvelopeId>()

    class Sent(val messageId: MessageId, val envelopeId: EnvelopeId)

    @Synchronized
    fun send(body: String, lifetimeSeconds: Int = Envelope.DEFAULT_LIFETIME_SECONDS): Sent {
        val text = MessageRules.normalizeBody(body)
        val token = AckTokens.newToken()
        val plain = ByteBuffer.allocate(2 + token.size + text.toByteArray().size)
            .put(PAYLOAD_VERSION).put(KIND_TEXT).put(token).put(text.toByteArray()).array()
        val inner = pairwise.encrypt(remote, plain)

        val createdAt = nowMillis() / 1000
        val tokenHash = AckTokens.hash(token)
        val tag = DestTags.forSending(keys.tagKey, createdAt)
        // L'AAD ne dépend pas du payload : on la calcule sur une enveloppe provisoire.
        val aad = Envelope(EnvelopeKind.DATA, tag, createdAt, lifetimeSeconds, ack = tokenHash, payload = ByteArray(Envelope.MIN_PAYLOAD_SIZE)).aad()
        val envelope = Envelope(
            EnvelopeKind.DATA, tag, createdAt, lifetimeSeconds, ack = tokenHash,
            payload = OuterLayer.seal(keys.envKey, aad, inner),
        )
        store.insert(envelope, Origin.SELF, createdAt)
        val messageId = MessageId(UUID.randomUUID().toString())
        pendingByTokenHash[tokenHash.hex()] = Pending(messageId, envelope.id, DeliveryState.QUEUED)
        ownEnvelopeIds += envelope.id
        return Sent(messageId, envelope.id)
    }

    /** Vrai si l'étiquette de [envelope] est celle de cette conversation (dans les deux sens). */
    fun handles(envelope: Envelope): Boolean = DestTags.matches(keys.tagKey, envelope)

    /** Marque un envoi comme transmis à un pair (appelé par le moteur de synchronisation). */
    @Synchronized
    fun markRelayed(envelopeId: EnvelopeId) {
        pendingByTokenHash.values.firstOrNull { it.envelopeId == envelopeId && it.state == DeliveryState.QUEUED }
            ?.state = DeliveryState.RELAYED
    }

    @Synchronized
    fun deliveryState(messageId: MessageId): DeliveryState? =
        pendingByTokenHash.values.firstOrNull { it.messageId == messageId }?.state

    /** Traite une enveloppe déjà acceptée par le store. Renvoie null si elle ne concerne pas cette conversation. */
    @Synchronized
    fun handle(envelope: Envelope): DirectEvent? {
        if (!handles(envelope)) return null
        if (envelope.id in ownEnvelopeIds) return DirectEvent.Rejected(DirectEvent.Reason.OWN_ENVELOPE)
        return when (envelope.kind) {
            EnvelopeKind.DATA -> receiveData(envelope)
            EnvelopeKind.ACK -> receiveAck(envelope)
            EnvelopeKind.ANNOUNCE -> null
        }
    }

    private fun receiveData(envelope: Envelope): DirectEvent {
        val inner = OuterLayer.open(keys.envKey, envelope.aad(), envelope.payload)
            ?: return DirectEvent.Rejected(DirectEvent.Reason.UNDECRYPTABLE)
        val plain = try {
            pairwise.decrypt(inner, remote).plaintext
        } catch (e: PairwiseDecryptionException) {
            return DirectEvent.Rejected(DirectEvent.Reason.UNDECRYPTABLE)
        }
        if (plain.size < 2 + Envelope.FIELD_SIZE || plain[0] != PAYLOAD_VERSION || plain[1] != KIND_TEXT) {
            return DirectEvent.Rejected(DirectEvent.Reason.MALFORMED)
        }
        // Le jeton est authentique : il est dans le payload chiffré. Le champ ack de l'en-tête doit en être le hachage.
        val token = plain.copyOfRange(2, 2 + Envelope.FIELD_SIZE)
        if (!AckTokens.hash(token).contentEquals(envelope.ack)) return DirectEvent.Rejected(DirectEvent.Reason.MALFORMED)
        val body = String(plain, 2 + Envelope.FIELD_SIZE, plain.size - 2 - Envelope.FIELD_SIZE, Charsets.UTF_8)

        publishAck(token)
        store.markDelivered(envelope.id)
        return DirectEvent.Received(envelope.id, body, envelope.createdAt * 1000)
    }

    private fun publishAck(token: ByteArray) {
        val createdAt = nowMillis() / 1000
        val tag = DestTags.forSending(keys.tagKey, createdAt)
        val aad = Envelope(EnvelopeKind.ACK, tag, createdAt, ACK_LIFETIME_SECONDS, ack = token, payload = ByteArray(Envelope.MIN_PAYLOAD_SIZE)).aad()
        // Le corps est vide : l'ACK ne prouve rien d'autre que la connaissance du jeton.
        val ack = Envelope(
            EnvelopeKind.ACK, tag, createdAt, ACK_LIFETIME_SECONDS, ack = token,
            payload = OuterLayer.seal(keys.envKey, aad, ByteArray(0)),
        )
        ownEnvelopeIds += ack.id
        store.insert(ack, Origin.SELF, createdAt)
    }

    private fun receiveAck(envelope: Envelope): DirectEvent {
        val token = envelope.ack
        if (token.all { it == 0.toByte() }) return DirectEvent.Rejected(DirectEvent.Reason.MALFORMED)
        val pending = pendingByTokenHash[AckTokens.hash(token).hex()]
            ?: return DirectEvent.Rejected(DirectEvent.Reason.UNKNOWN_ACK_TOKEN)
        if (pending.state == DeliveryState.ACKNOWLEDGED) return DirectEvent.Rejected(DirectEvent.Reason.UNKNOWN_ACK_TOKEN)
        pending.state = DeliveryState.ACKNOWLEDGED
        store.applyAck(token) // retire notre propre copie de la DATA
        return DirectEvent.Acknowledged(pending.messageId)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        private const val PAYLOAD_VERSION: Byte = 1
        private const val KIND_TEXT: Byte = 1

        /** Durée de vie d'un `ACK` : 6 h, comme un message par défaut. */
        const val ACK_LIFETIME_SECONDS = 6 * 3600
    }
}
