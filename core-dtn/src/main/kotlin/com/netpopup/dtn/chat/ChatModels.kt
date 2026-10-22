package com.netpopup.dtn.chat

/** Identifiant local et opaque d'une conversation (room, 1:1 ou room locale). */
@JvmInline
value class ConversationId(val value: String)

/** La room locale publique : toujours présente, jamais quittée. */
val LOCAL_CONVERSATION_ID = ConversationId("local")

/** Identifiant local d'un message affiché. */
@JvmInline
value class MessageId(val value: String)

enum class ConversationKind {
    /** Room de proximité ouverte à tous : signée, jamais confidentielle. */
    LOCAL_PUBLIC,

    /** Room privée : Sender Keys, accès sur demande avec un code. */
    ROOM,

    /** Conversation 1:1 : X3DH puis Double Ratchet. */
    DIRECT
}

/** Qui peut accepter une demande d'accès à une room privée. */
enum class AdmissionPolicy { ANY_MEMBER, CREATOR_ONLY }

/**
 * État de livraison d'un message, vu par l'appareil.
 *
 * [ACKNOWLEDGED] n'existe qu'en 1:1 : les rooms n'ont pas d'acquittement.
 */
enum class DeliveryState {
    /** Créé localement, encore dans le store, transmis à aucun pair. */
    QUEUED,

    /** Transmis à au moins un pair. */
    RELAYED,

    /** Acquitté par le destinataire (1:1 uniquement). */
    ACKNOWLEDGED,

    /** Message d'un autre utilisateur, reçu et déchiffré. */
    RECEIVED
}

data class Conversation(
    val id: ConversationId,
    val kind: ConversationKind,
    val title: String,
    /** Null hors des rooms privées. */
    val admission: AdmissionPolicy? = null,
    /** Vrai tant qu'une rotation de clés après un retrait de membre n'est pas terminée. */
    val keyRotationPending: Boolean = false
) {
    /** La room locale est publique : l'UI doit toujours l'indiquer. */
    val isPublic: Boolean get() = kind == ConversationKind.LOCAL_PUBLIC
}

data class ChatMessage(
    val id: MessageId,
    val conversationId: ConversationId,
    /** Empreinte de la clé d'identité de l'émetteur — l'identité réelle. */
    val senderKey: String,
    /** Pseudo choisi par l'émetteur, simple nom d'affichage. */
    val displayName: String,
    val body: String,
    /** Horodatage de création selon l'horloge de l'émetteur (epoch ms). */
    val sentAt: Long,
    /** Fin de vie du texte en clair sur cet appareil (epoch ms). */
    val expiresAt: Long,
    val isOwn: Boolean,
    val deliveryState: DeliveryState,
    val readAt: Long? = null
)

/** Room créée localement, avec le code à partager pour demander l'accès. */
data class RoomInvite(
    val conversationId: ConversationId,
    val code: String
)

/** Demande d'accès envoyée par cet appareil, en attente d'un membre. */
data class PendingJoin(
    val code: String,
    val requestedAt: Long
)

/** Demande d'accès reçue d'un tiers pour une room dont on est membre. */
data class JoinRequest(
    val id: String,
    val conversationId: ConversationId,
    val requesterKey: String,
    val displayName: String,
    val receivedAt: Long
)

/** État du mesh affiché dans l'UI. */
data class MeshStatus(
    val isRunning: Boolean,
    val peersInRange: Int
) {
    companion object {
        val STOPPED = MeshStatus(isRunning = false, peersInRange = 0)
    }
}

/** Membre d'une room privée, identifié par l'empreinte de sa clé d'identité. */
data class RoomMember(
    val identityKey: String,
    val displayName: String,
    val isSelf: Boolean
)

/**
 * Ce que l'écran d'une room privée affiche en plus des messages : les membres, l'empreinte de
 * sécurité à comparer en personne, et l'état de la rotation après un retrait.
 */
data class RoomDetails(
    val members: List<RoomMember>,
    /** Empreinte affichable, groupes de chiffres séparés par des espaces. */
    val securityFingerprint: String,
    val keyRotationPending: Boolean
)
