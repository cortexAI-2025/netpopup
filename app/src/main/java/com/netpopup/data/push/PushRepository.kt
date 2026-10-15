package com.netpopup.data.push

import com.google.firebase.messaging.FirebaseMessaging
import com.netpopup.data.local.UserPreferences
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FCM topic subscriptions — one topic per room ("room_<roomId>").
 *
 * The `notifyOnMessage` Cloud Function (functions/index.js) publishes a
 * data-only message to that topic for every new message; the client then
 * decides in [com.netpopup.service.NetPopUpMessagingService] whether to show it.
 *
 * Failures are swallowed: push is best-effort and must never block chatting.
 */
@Singleton
class PushRepository @Inject constructor(
    private val messaging: FirebaseMessaging,
    private val userPreferences: UserPreferences
) {

    /** Subscribes to the current geo zone and drops the previous zone's topic. */
    suspend fun subscribeToLocalRoom(roomId: String) {
        val topic = topicFor(roomId)
        val previous = userPreferences.getLocalTopic()
        if (previous != null && previous != topic) {
            runCatching { messaging.unsubscribeFromTopic(previous).await() }
        }
        if (runCatching { messaging.subscribeToTopic(topic).await() }.isSuccess) {
            userPreferences.setLocalTopic(topic)
        }
    }

    suspend fun subscribeToRoom(roomId: String) {
        runCatching { messaging.subscribeToTopic(topicFor(roomId)).await() }
    }

    suspend fun unsubscribeFromRoom(roomId: String) {
        runCatching { messaging.unsubscribeFromTopic(topicFor(roomId)).await() }
    }

    companion object {
        /** Must match the topic name used by the Cloud Function. */
        fun topicFor(roomId: String) = "room_$roomId"
    }
}
