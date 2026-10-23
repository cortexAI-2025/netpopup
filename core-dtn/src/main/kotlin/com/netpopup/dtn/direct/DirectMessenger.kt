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

    /** Message de contrôle (`ROOM_KEY`, Sender Key…) déchiffré et acquitté ; invisible pour l'utilisateur. */
    class ControlReceived(val envelopeId: EnvelopeId, val payload: ByteArray) : DirectEvent

    /** Un message envoyé par cet appareil vient d'être acquitté par son destinataire. */
    data class Acknowledged(val messageId: MessageId) : DirectEvent

    data class Rejected(val reason: Reason) : DirectEvent

    enum class Reason { NOT_FOR_THIS_CONVERSATION, OWN_ENVELOPE, UNDECRYPTABLE, MALFORMED, UNKNOWN_ACK_TOKEN }
}

/** Un envoi dont l'acquittement est attendu ; persisté pour survivre à un redémarrage. */
data class PendingSend(
    val tokenHash: String,
    val messageId: MessageId,
    val envelopeId: EnvelopeId,
    val state: DeliveryState,
    /** Vrai pour un message de contrôle : pas de ligne de message à mettre à jour. */
    val control: Boolean
)

/** Stockage des [PendingSend] d'un contact (clé : empreinte hexadécimale de sa clé d'identité). */
interface PendingSendStore {
    fun load(peer: String): List<PendingSend>
    fun save(peer: String, send: PendingSend)
}

object NoPendingSendStore : PendingSendStore {
    override fun load(peer: String) = emptyList<PendingSend>()
    override fun save(peer: String, send: PendingSend) = Unit
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
    private val pendingStore: PendingSendStore = NoPendingSendStore,
) {
    private val keys = PairwiseSession.pairKeys(local.keyPair, remote)
    private val peerId = remote.serialize().joinToString("") { "%02x".format(it) }

    private val pendingByTokenHash = HashMap<String, PendingSend>()
    private val ownEnvelopeIds = HashSet<EnvelopeId>()

    init {
        // Après un redémarrage, les acquittements attendus reprennent là où ils en étaient.
        pendingStore.load(peerId).forEach {
            pendingByTokenHash[it.tokenHash] = it
            ownEnvelopeIds += it.envelopeId
        }
    }

    class Sent(val messageId: MessageId, val envelopeId: EnvelopeId)

    @Synchronized
    fun send(body: String, lifetimeSeconds: Int = Envelope.DEFAULT_LIFETIME_SECONDS): Sent =
        sendPayload(KIND_TEXT, MessageRules.normalizeBody(body).toByteArray(), lifetimeSeconds, control = false)

    /**
     * Envoie un message de contrôle (`ROOM_KEY`, Sender Key…). Il est acquitté comme un texte, mais n'apparaît
     * jamais dans une conversation. Taille maximale : celle de l'enveloppe, moins les en-têtes.
     */
    @Synchronized
    fun sendControl(payload: ByteArray, lifetimeSeconds: Int = Envelope.DEFAULT_LIFETIME_SECONDS): Sent =
        sendPayload(KIND_CONTROL, payload, lifetimeSeconds, control = true)

    private fun sendPayload(kind: Byte, content: ByteArray, lifetimeSeconds: Int, control: Boolean): Sent {
        val token = AckTokens.newToken()
        val plain = ByteBuffer.allocate(2 + token.size + content.size)
            .put(PAYLOAD_VERSION).put(kind).put(token).put(content).array()
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
        val pending = PendingSend(tokenHash.hex(), messageId, envelope.id, DeliveryState.QUEUED, control)
        pendingByTokenHash[pending.tokenHash] = pending
        pendingStore.save(peerId, pending)
        ownEnvelopeIds += envelope.id
        return Sent(messageId, envelope.id)
    }

    /**
     * Clé d'étiquette de cette conversation. Réservée au moteur : un transport ne doit jamais la recevoir, seulement
     * des étiquettes calculées (voir `MeshEngine.listeningTags`).
     */
    val tagKey: ByteArray get() = keys.tagKey.copyOf()

    /** Vrai si l'étiquette de [envelope] est celle de cette conversation (dans les deux sens). */
    fun handles(envelope: Envelope): Boolean = DestTags.matches(keys.tagKey, envelope)

    /** Marque un envoi comme transmis à un pair (appelé par le moteur de synchronisation). */
    @Synchronized
    fun markRelayed(envelopeId: EnvelopeId): MessageId? {
        val pending = pendingByTokenHash.values.firstOrNull { it.envelopeId == envelopeId && it.state == DeliveryState.QUEUED }
            ?: return null
        val updated = pending.copy(state = DeliveryState.RELAYED)
        pendingByTokenHash[updated.tokenHash] = updated
        pendingStore.save(peerId, updated)
        return updated.messageId.takeUnless { updated.control }
    }

    /** Vrai si cette enveloppe est un envoi de cet appareil vers ce contact (relayée par des pairs, elle revient). */
    fun owns(envelopeId: EnvelopeId): Boolean = envelopeId in ownEnvelopeIds

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
        if (plain.size < 2 + Envelope.FIELD_SIZE || plain[0] != PAYLOAD_VERSION || (plain[1] != KIND_TEXT && plain[1] != KIND_CONTROL)) {
            return DirectEvent.Rejected(DirectEvent.Reason.MALFORMED)
        }
        // Le jeton est authentique : il est dans le payload chiffré. Le champ ack de l'en-tête doit en être le hachage.
        val token = plain.copyOfRange(2, 2 + Envelope.FIELD_SIZE)
        if (!AckTokens.hash(token).contentEquals(envelope.ack)) return DirectEvent.Rejected(DirectEvent.Reason.MALFORMED)
        val content = plain.copyOfRange(2 + Envelope.FIELD_SIZE, plain.size)

        publishAck(token)
        store.markDelivered(envelope.id)
        return if (plain[1] == KIND_CONTROL) DirectEvent.ControlReceived(envelope.id, content)
        else DirectEvent.Received(envelope.id, String(content, Charsets.UTF_8), envelope.createdAt * 1000)
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
        val updated = pending.copy(state = DeliveryState.ACKNOWLEDGED)
        pendingByTokenHash[updated.tokenHash] = updated
        pendingStore.save(peerId, updated)
        store.applyAck(token) // retire notre propre copie de la DATA
        return DirectEvent.Acknowledged(pending.messageId)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        private const val PAYLOAD_VERSION: Byte = 1
        private const val KIND_TEXT: Byte = 1
        private const val KIND_CONTROL: Byte = 2

        /** Durée de vie d'un `ACK` : 6 h, comme un message par défaut. */
        const val ACK_LIFETIME_SECONDS = 6 * 3600
    }
}
