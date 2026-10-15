package com.netpopup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.netpopup.data.model.RoomType
import com.netpopup.service.NetPopUpMessagingService
import com.netpopup.ui.navigation.NavGraph
import com.netpopup.ui.theme.NetPopUpTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host. All navigation is handled by Jetpack Navigation Compose.
 *
 * Also:
 *  - asks for POST_NOTIFICATIONS on Android 13+ (push is opt-in there),
 *  - opens the private room carried by a tapped notification.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var pendingPrivateRoomId by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleNotificationIntent(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            NetPopUpTheme {
                NavGraph(
                    pendingPrivateRoomId  = pendingPrivateRoomId,
                    onPendingRoomConsumed = { pendingPrivateRoomId = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val roomId   = intent?.getStringExtra(NetPopUpMessagingService.EXTRA_ROOM_ID) ?: return
        val roomType = intent.getStringExtra(NetPopUpMessagingService.EXTRA_ROOM_TYPE)
        // Local rooms are the start destination already — only private rooms need routing
        if (roomType == RoomType.PRIVATE.name) pendingPrivateRoomId = roomId
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
