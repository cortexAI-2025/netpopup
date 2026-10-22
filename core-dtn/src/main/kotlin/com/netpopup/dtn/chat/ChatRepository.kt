package com.netpopup.dtn.chat

import kotlinx.coroutines.flow.Flow

/**
 * Seule porte d'entrée de l'UI vers la messagerie.
 *
 * Le contrat ne mentionne ni transport, ni enveloppe, ni crypto, ni Firebase :
 * les ViewModels ne voient que des conversations et des messages en clair.
 * L'implémentation réelle (cœur DTN + crypto) arrive avec les issues 4 à 12 ;
 * un faux en mémoire est fourni dans les test fixtures de ce module.
 *
 * Toutes les fonctions `suspend` sont sûres à appeler depuis le thread principal.
 */
interface ChatRepository {

    /** État du mesh (actif, nombre de pairs à portée). */
    val meshStatus: Flow<MeshStatus>

    /** Conversations de l'appareil ; la room locale publique est toujours présente. */
    fun observeConversations(): Flow<List<Conversation>>

    /**
     * Messages non expirés d'une conversation, du plus ancien au plus récent.
     * La liste est ré-émise quand un message expire, même si rien d'autre ne change.
     */
    fun observeMessages(conversationId: ConversationId): Flow<List<ChatMessage>>

    /**
     * Envoie un texte. Le message apparaît aussitôt avec l'état [DeliveryState.QUEUED],
     * même sans aucun pair à portée.
     *
     * @throws InvalidMessageException si le texte est vide ou trop long ([MessageRules]).
     * @throws UnknownConversationException si la conversation n'existe pas.
     */
    suspend fun sendText(conversationId: ConversationId, body: String): ChatMessage

    /** Marque comme lus tous les messages reçus de la conversation. */
    suspend fun markRead(conversationId: ConversationId)

    /** Retire un message de cet appareil seulement ; l'enveloppe continue sa route. */
    suspend fun deleteLocally(messageId: MessageId)

    /** Crée une room privée dont cet appareil est le créateur. */
    suspend fun createRoom(
        title: String,
        admission: AdmissionPolicy = AdmissionPolicy.ANY_MEMBER
    ): RoomInvite

    /**
     * Demande l'accès à une room avec son lien d'invitation complet. Le lien ne donne aucun accès aux
     * messages : il faut qu'un membre autorisé accepte.
     *
     * @throws InvalidInviteException si le lien est mal formé, altéré ou n'est pas une invitation.
     */
    suspend fun requestToJoin(inviteLink: String): PendingJoin

    /**
     * Ajoute un contact depuis son lien (QR en personne, ou annonce) et ouvre la conversation 1:1.
     *
     * @throws InvalidInviteException si le lien est mal formé ou si ses signatures sont fausses.
     */
    suspend fun addContact(contactLink: String): ConversationId

    /** Notre lien de contact, à montrer en QR ; il change quand la pré-clé hebdomadaire est renouvelée. */
    suspend fun myContactLink(): String

    /** Demandes d'accès envoyées par cet appareil et pas encore acceptées. */
    fun observePendingJoins(): Flow<List<PendingJoin>>

    /** Demandes d'accès reçues pour les rooms où cet appareil peut admettre. */
    fun observeJoinRequests(): Flow<List<JoinRequest>>

    suspend fun approveJoinRequest(requestId: String)

    suspend fun rejectJoinRequest(requestId: String)

    /** Membres, empreinte de sécurité et rotation en cours d'une room privée ; null pour les autres conversations. */
    fun observeRoomDetails(conversationId: ConversationId): Flow<RoomDetails?>

    /**
     * Quitte une room ou supprime une conversation 1:1, avec ses messages et les
     * demandes d'accès reçues pour elle. Sans effet sur la room locale.
     */
    suspend fun leave(conversationId: ConversationId)
}

class InvalidMessageException(message: String) : IllegalArgumentException(message)

/** Lien d'invitation ou de contact mal formé, altéré ou d'un autre type. */
class InvalidInviteException(message: String) : IllegalArgumentException(message)

class UnknownConversationException(id: ConversationId) :
    NoSuchElementException("Unknown conversation: ${id.value}")
