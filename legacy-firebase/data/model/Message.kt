package com.netpopup.data.model

/**
 * A single text message inside a room.
 *
 * [expiresAt] drives the ephemeral TTL.  Firestore TTL policy (set on the
 * 'expiresAt' field in the Firestore console) can auto-delete expired docs
 * without any Cloud Function.
 *
 * [reported] is set to true when a user flags the message — a simple
 * moderation signal for admin review.
 */
data class Message(
    val id: String = "",
    val roomId: String = "",
    val userId: String = "",
    val username: String = "",
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val reported: Boolean = false,
    /** Default TTL: 1 hour */
    val expiresAt: Long = System.currentTimeMillis() + TTL_MS
) {
    companion object {
        const val TTL_MS = 60L * 60L * 1_000L   // 1 hour
        const val MAX_LENGTH = 500
    }
}
