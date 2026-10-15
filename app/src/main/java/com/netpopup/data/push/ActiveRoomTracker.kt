package com.netpopup.data.push

/**
 * Remembers which room is currently on screen (and resumed) so that push
 * notifications for that room are not shown — the user is already reading it.
 */
object ActiveRoomTracker {
    @Volatile
    var activeRoomId: String? = null
}
