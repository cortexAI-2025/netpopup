package com.netpopup.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.netpopup.MainActivity
import com.netpopup.R
import com.netpopup.dtn.chat.MeshStatus
import com.netpopup.dtn.presentation.ActiveConversation
import com.netpopup.dtn.presentation.NotificationPolicy
import com.netpopup.settings.AppSettings
import kotlinx.coroutines.flow.first
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Service de premier plan de type `connectedDevice` : sans lui, Android coupe la
 * découverte Bluetooth dès que l'app passe en arrière-plan.
 *
 * Sa notification est permanente (obligatoire) et **ne dit rien des messages** : un état
 * du mesh et un nombre de téléphones connectés, masquée sur l'écran verrouillé.
 *
 * Il refuse de démarrer sans les permissions Nearby ; l'appelant doit les avoir demandées
 * avant [start] (sur Android 14, démarrer un service `connectedDevice` sans elles lève
 * une exception).
 */
@AndroidEntryPoint
class MeshService : Service() {

    @Inject lateinit var runtime: MeshRuntime
    @Inject lateinit var settings: AppSettings

    private lateinit var notifier: MessageNotifier
    private var notifyJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifier = MessageNotifier(this).also { it.createChannel() }
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.mesh_channel_name), NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Un service lancé par startForegroundService doit appeler startForeground très vite,
        // même s'il s'arrête aussitôt après : sinon Android l'arrête de force.
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(MeshStatus.STOPPED),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } catch (e: Exception) {
            // Permission manquante (Android 14) ou démarrage interdit depuis l'arrière-plan
            stopSelf()
            return START_NOT_STICKY
        }
        if (!settings.snapshot.meshEnabled || MeshPermissions.missing(this).isNotEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        runtime.start()
        statusJob?.cancel()
        statusJob = scope.launch {
            runtime.status.collect { status ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
            }
        }
        notifyJob?.cancel()
        notifyJob = scope.launch {
            val repository = runtime.repository
            repository.observeIncoming().collect { message ->
                val blocked = repository.observeBlocked().first()
                if (NotificationPolicy.shouldNotify(message, settings.snapshot.notificationsEnabled, blocked, ActiveConversation.current.value)) {
                    notifier.show()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        statusJob?.cancel()
        notifyJob?.cancel()
        scope.cancel()
        runtime.stop()
        super.onDestroy()
    }

    private fun notification(status: MeshStatus): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (status.isRunning) getString(R.string.mesh_notification_peers, status.peersInRange)
        else getString(R.string.mesh_notification_starting)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_netpopup)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "netpopup_mesh"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context): Boolean {
            if (MeshPermissions.missing(context).isNotEmpty()) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
                true
            } catch (_: SecurityException) {
                false // Permissions may be revoked between the check and Android starting the service.
            } catch (_: IllegalStateException) {
                false // Android may reject a foreground-service request after the activity stops.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshService::class.java))
        }
    }
}
