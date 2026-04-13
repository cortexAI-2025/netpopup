package com.netpopup.data.model

/** Discriminates between geo-based and invite-only rooms. */
enum class RoomType { LOCAL, PRIVATE }

/**
 * A chatroom document stored in Firestore /rooms/{id}.
 *
 * LOCAL rooms are keyed by [zoneId] (derived from rounded lat/lng).
 * PRIVATE rooms are identified by a short [code] shared out-of-band.
 */
data class Room(
    val id: String = "",
    val type: RoomType = RoomType.LOCAL,
    /** Geo-zone key — only meaningful for LOCAL rooms */
    val zoneId: String = "",
    /** Invite code — only meaningful for PRIVATE rooms */
    val code: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
