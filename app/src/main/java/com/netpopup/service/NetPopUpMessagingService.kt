package com.netpopup.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.netpopup.MainActivity
import com.netpopup.R
import com.netpopup.data.local.UserPreferences
import com.netpopup.data.push.ActiveRoomTracker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Handles incoming FCM push notifications.
 *
 * Sent by the `notifyOnMessage` Cloud Function as a data-only message on the
 * room topic ("room_<roomId>"):
 *   { "roomId": "XK7B2Q", "roomType": "PRIVATE", "senderId": "<uid>",
 *     "title": "Eagle_7312", "body": "Hey everyone!" }
 *
 * Data-only messages always reach [onMessageReceived] (foreground and
 * background), so the filtering below applies in every case:
 *   - notifications disabled in Settings,
 *   - own messages,
 *   - the room currently on screen.
 */
@AndroidEntryPoint
class NetPopUpMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var userPreferences: UserPreferences

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        // Topic subscriptions survive token refreshes; the token is kept for
        // future direct targeting from a backend.
        serviceScope.launch {
            userPreferences.saveFcmToken(token)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data     = remoteMessage.data
        val roomId   = data["roomId"]
        val roomType = data["roomType"]
        val senderId = data["senderId"]

        if (roomId != null && roomId == ActiveRoomTracker.activeRoomId) return

        // onMessageReceived runs on a background thread — blocking is fine here
        val (enabled, selfId) = runBlocking {
            userPreferences.isNotificationsEnabled() to userPreferences.getUserId()
        }
        if (!enabled) return
        if (senderId != null && senderId == selfId) return
        if (senderId != null && runBlocking { userPreferences.isUserBlocked(senderId) }) return

        val title = data["title"]
            ?: remoteMessage.notification?.title
            ?: "NetPopUp"
        val body  = data["body"]
            ?: remoteMessage.notification?.body
            ?: ""

        showNotification(title, body, roomId, roomType)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun showNotification(title: String, body: String, roomId: String?, roomType: String?) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel (required on Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "New message notifications" }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_ROOM_ID, roomId)
            putExtra(EXTRA_ROOM_TYPE, roomType)
        }
        // One PendingIntent per room so extras are not overwritten between rooms
        val requestCode = roomId?.hashCode() ?: 0
        val pendingIntent = PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_stat_netpopup)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // One notification per room: a new message replaces the previous one
        manager.notify(roomId ?: TAG_GENERIC, 0, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.coroutineContext.cancel()
    }

    companion object {
        const val CHANNEL_ID      = "netpopup_messages"
        const val EXTRA_ROOM_ID   = "com.netpopup.extra.ROOM_ID"
        const val EXTRA_ROOM_TYPE = "com.netpopup.extra.ROOM_TYPE"
        private const val TAG_GENERIC = "netpopup"
    }
}
