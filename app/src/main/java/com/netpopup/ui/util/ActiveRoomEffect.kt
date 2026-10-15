package com.netpopup.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.netpopup.data.push.ActiveRoomTracker

/**
 * Marks [roomId] as the room on screen while the host is resumed, so that
 * push notifications for it are suppressed. Cleared on pause / dispose.
 */
@Composable
fun ActiveRoomEffect(roomId: String?) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(roomId, lifecycleOwner) {
        if (roomId == null) return@DisposableEffect onDispose {}

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> ActiveRoomTracker.activeRoomId = roomId
                Lifecycle.Event.ON_PAUSE  -> if (ActiveRoomTracker.activeRoomId == roomId) {
                    ActiveRoomTracker.activeRoomId = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            ActiveRoomTracker.activeRoomId = roomId
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (ActiveRoomTracker.activeRoomId == roomId) ActiveRoomTracker.activeRoomId = null
        }
    }
}
