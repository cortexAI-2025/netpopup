package com.netpopup.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.netpopup.data.model.Room
import com.netpopup.data.model.RoomType
import com.netpopup.util.ModerationUtils
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles creation and lookup of rooms in Firestore.
 *
 * Collection: /rooms
 * Documents:
 *   - LOCAL  rooms: document ID == zoneId
 *   - PRIVATE rooms: document ID == randomly generated code
 */
@Singleton
class RoomRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {

    private val roomsCollection get() = firestore.collection("rooms")

    // ── Local (Geo) rooms ─────────────────────────────────────────────────────

    /**
     * Returns the local room for [zoneId], creating it if it does not exist.
     * Using the zoneId as the document ID makes the upsert idempotent.
     */
    suspend fun getOrCreateLocalRoom(zoneId: String): Room {
        val docRef = roomsCollection.document(zoneId)
        val snapshot = docRef.get().await()

        if (snapshot.exists()) {
            return snapshot.toRoom()
        }

        val room = Room(
            id = zoneId,
            type = RoomType.LOCAL,
            zoneId = zoneId,
            createdAt = System.currentTimeMillis()
        )
        docRef.set(room.toMap()).await()
        return room
    }

    // ── Private rooms ─────────────────────────────────────────────────────────

    /**
     * Creates a new private room with a unique 6-character code.
     * Retries up to 3 times if a collision occurs (extremely rare).
     */
    suspend fun createPrivateRoom(): Room {
        repeat(3) { attempt ->
            val code = ModerationUtils.generateRoomCode()
            val docRef = roomsCollection.document(code)
            val existing = docRef.get().await()
            if (existing.exists()) return@repeat  // collision, retry

            val room = Room(
                id = code,
                type = RoomType.PRIVATE,
                code = code,
                createdAt = System.currentTimeMillis()
            )
            docRef.set(room.toMap()).await()
            return room
        }
        error("Could not generate a unique room code after 3 attempts")
    }

    /**
     * Joins an existing private room by its [code].
     * Returns null if no room matches the code.
     */
    suspend fun joinPrivateRoom(code: String): Room? {
        val snapshot = roomsCollection.document(code.uppercase()).get().await()
        return if (snapshot.exists()) snapshot.toRoom() else null
    }

    // ── Serialization helpers ─────────────────────────────────────────────────

    private fun Room.toMap(): Map<String, Any> = mapOf(
        "id"        to id,
        "type"      to type.name,
        "zoneId"    to zoneId,
        "code"      to code,
        "createdAt" to createdAt
    )

    private fun com.google.firebase.firestore.DocumentSnapshot.toRoom(): Room = Room(
        id        = getString("id") ?: id,
        type      = RoomType.valueOf(getString("type") ?: "LOCAL"),
        zoneId    = getString("zoneId") ?: "",
        code      = getString("code") ?: "",
        createdAt = getLong("createdAt") ?: 0L
    )
}
