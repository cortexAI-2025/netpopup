package com.netpopup.mesh

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.netpopup.MainActivity
import com.netpopup.R
import com.netpopup.dtn.presentation.NotificationPolicy

/**
 * Notification locale d'un message reçu (pas de FCM). Texte fixe, masquée sur l'écran verrouillé, et une
 * seule à la fois : une nouvelle remplace la précédente, ce qui regroupe naturellement les rafales.
 */
class MessageNotifier(private val context: Context) {

    fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.messages_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
            .apply {
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_netpopup)
            .setContentTitle(NotificationPolicy.TEXT)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        NotificationManagerCompat.from(context).notify(ID, notification)
    }

    fun clear() = NotificationManagerCompat.from(context).cancel(ID)

    companion object {
        private const val CHANNEL_ID = "netpopup_messages"
        const val ID = 1002
    }
}
