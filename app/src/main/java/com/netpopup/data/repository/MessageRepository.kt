package com.netpopup.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.netpopup.data.model.Message
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real-time message operations backed by Firestore.
 *
 * Collection: /messages
 * Each document maps 1:1 to a [Message].
 *
 * Query strategy for real-time delivery (<300ms perceived):
 *  - Uses a Firestore snapshot listener (not polling).
 *  - Limits to the most recent 100 messages per room.
 *  - Filters out already-expired messages client-side (server TTL handles deletion).
 */
@Singleton
class MessageRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {

    private val messagesCollection get() = firestore.collection("messages")

    // ── Real-time listener ────────────────────────────────────────────────────

    /**
     * Returns a [Flow] that emits the latest [limit] non-expired messages
     * for [roomId] whenever Firestore pushes an update.
     *
     * The flow is cold — the listener is only active while the flow is
     * collected (typically the lifetime of the chat screen).
     */
    fun observeMessages(roomId: String, limit: Long = 100): Flow<List<Message>> =
        callbackFlow {
            var registration: ListenerRegistration? = null
            val now = System.currentTimeMillis()

            registration = messagesCollection
                .whereEqualTo("roomId", roomId)
                .whereGreaterThan("expiresAt", now)    // ephemeral filter
                .orderBy("expiresAt")                  // must match compound index
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .limitToLast(limit)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        close(error)
                        return@addSnapshotListener
                    }
                    val messages = snapshot?.documents
                        ?.mapNotNull { it.toMessage() }
                        ?: emptyList()
                    trySend(messages)
                }

            awaitClose { registration?.remove() }
        }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Sends a new message.  Returns the Firestore document ID.
     *
     * Content must already be sanitized and validated before calling this.
     */
    suspend fun sendMessage(
        roomId: String,
        userId: String,
        username: String,
        content: String
    ): String {
        val docRef = messagesCollection.document()          // auto-ID
        val message = Message(
            id       = docRef.id,
            roomId   = roomId,
            userId   = userId,
            username = username,
            content  = content,
            timestamp = System.currentTimeMillis()
        )
        docRef.set(message.toMap()).await()
        return docRef.id
    }

    /**
     * Flags a message as reported.  Admins can query reported == true.
     */
    suspend fun reportMessage(messageId: String) {
        messagesCollection.document(messageId)
            .update("reported", true)
            .await()
    }

    /**
     * Deletes a message by ID (admin / room-owner action).
     */
    suspend fun deleteMessage(messageId: String) {
        messagesCollection.document(messageId).delete().await()
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    private fun Message.toMap(): Map<String, Any> = mapOf(
        "id"        to id,
        "roomId"    to roomId,
        "userId"    to userId,
        "username"  to username,
        "content"   to content,
        "timestamp" to timestamp,
        "reported"  to reported,
        "expiresAt" to expiresAt
    )

    private fun com.google.firebase.firestore.DocumentSnapshot.toMessage(): Message? {
        return try {
            Message(
                id        = getString("id") ?: id,
                roomId    = getString("roomId") ?: return null,
                userId    = getString("userId") ?: return null,
                username  = getString("username") ?: "Unknown",
                content   = getString("content") ?: return null,
                timestamp = getLong("timestamp") ?: 0L,
                reported  = getBoolean("reported") ?: false,
                expiresAt = getLong("expiresAt") ?: 0L
            )
        } catch (e: Exception) {
            null
        }
    }
}
