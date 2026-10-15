package com.netpopup.ui.screen.local

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.netpopup.data.local.UserPreferences
import com.netpopup.data.model.Message
import com.netpopup.data.model.Room
import com.netpopup.data.model.User
import com.netpopup.data.push.PushRepository
import com.netpopup.data.repository.AuthRepository
import com.netpopup.data.repository.MessageRepository
import com.netpopup.data.repository.RoomRepository
import com.netpopup.util.GeoUtils
import com.netpopup.util.ModerationUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

data class LocalChatUiState(
    val isLoading: Boolean          = true,
    val currentUser: User?          = null,
    val room: Room?                 = null,
    val zoneDisplay: String         = "",
    val messages: List<Message>     = emptyList(),
    val blockedUsers: Set<String>   = emptySet(),
    val error: String?              = null
)

/**
 * ViewModel for the Local (geo-based) chat screen.
 *
 * On creation it:
 *   1. Signs in anonymously (or restores an existing session).
 *   2. Waits for the screen to report the location permission result, then
 *      requests a coarse location from the FusedLocationProviderClient.
 *   3. Computes a zone ID and retrieves / creates the matching Firestore room.
 *   4. Subscribes to the room's push topic (and drops the previous zone's).
 *   5. Opens a Firestore snapshot listener and emits messages as a Flow.
 *
 * Rate-limit: 1 message per second, enforced client-side.
 */
@HiltViewModel
class LocalChatViewModel @Inject constructor(
    application: Application,
    private val authRepository: AuthRepository,
    private val messageRepository: MessageRepository,
    private val roomRepository: RoomRepository,
    private val userPreferences: UserPreferences,
    private val pushRepository: PushRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LocalChatUiState())
    val uiState: StateFlow<LocalChatUiState> = _uiState.asStateFlow()

    /** Timestamp of the last successfully sent message (rate-limit guard). */
    private var lastSentAt = 0L

    /** Completed by [onLocationPermissionResult] once the user has answered. */
    private val locationPermission = CompletableDeferred<Boolean>()

    init {
        viewModelScope.launch { initialize() }
    }

    // ── Initialization ────────────────────────────────────────────────────────

    private suspend fun initialize() {
        try {
            // 1. Ensure the user is signed in
            val user = authRepository.ensureSignedIn()
            _uiState.update { it.copy(currentUser = user) }

            // 2. Resolve geo zone (only once the permission dialog is answered,
            //    otherwise the first launch would always land in the default zone)
            locationPermission.await()
            val location = resolveLocation()
            val zoneId = if (location != null) {
                GeoUtils.computeZoneId(location.latitude, location.longitude)
            } else {
                // Fallback zone when location permission is denied
                "zone_default"
            }
            val zoneDisplay = if (location != null) {
                GeoUtils.zoneDisplay(location.latitude, location.longitude)
            } else {
                "Default Zone"
            }

            // 3. Get or create the local room
            val room = roomRepository.getOrCreateLocalRoom(zoneId)
            _uiState.update {
                it.copy(room = room, zoneDisplay = zoneDisplay, isLoading = false)
            }

            // 4. Push notifications for this zone (best-effort)
            viewModelScope.launch { pushRepository.subscribeToLocalRoom(room.id) }

            // 5. Observe messages + blocked users simultaneously
            combine(
                messageRepository.observeMessages(room.id),
                userPreferences.blockedUsersFlow
            ) { messages, blocked ->
                // Filter out blocked users' messages client-side
                messages.filter { it.userId !in blocked } to blocked
            }.collect { (filteredMessages, blocked) ->
                _uiState.update {
                    it.copy(messages = filteredMessages, blockedUsers = blocked)
                }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    /**
     * Fetches a single coarse location update.
     * Returns null if permission is missing or the request fails.
     */
    private suspend fun resolveLocation(): Location? {
        val ctx = getApplication<Application>()
        val hasPermission = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) return null

        return try {
            LocationServices
                .getFusedLocationProviderClient(ctx)
                .getCurrentLocation(Priority.PRIORITY_LOW_POWER, null)
                .await()
        } catch (e: Exception) {
            null
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    /** Called by the screen with the permission result (or `true` if already granted). */
    fun onLocationPermissionResult(granted: Boolean) {
        locationPermission.complete(granted)
    }

    fun sendMessage(content: String) {
        val now = System.currentTimeMillis()
        if (now - lastSentAt < 1_000L) {
            _uiState.update { it.copy(error = "Please wait before sending again") }
            return
        }

        val user   = _uiState.value.currentUser ?: return
        val roomId = _uiState.value.room?.id    ?: return
        val sanitized = ModerationUtils.sanitize(content)

        if (sanitized.isBlank()) return
        if (sanitized.length > Message.MAX_LENGTH) {
            _uiState.update { it.copy(error = "Message too long (max ${Message.MAX_LENGTH} chars)") }
            return
        }
        if (ModerationUtils.containsBannedWords(sanitized)) {
            _uiState.update { it.copy(error = "Message contains prohibited content") }
            return
        }

        lastSentAt = now
        clearError()

        viewModelScope.launch {
            try {
                // The username can be changed from Settings while this screen lives
                val username = userPreferences.usernameFlow.first() ?: user.username
                messageRepository.sendMessage(
                    roomId   = roomId,
                    userId   = user.id,
                    username = username,
                    content  = sanitized
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to send: ${e.message}") }
            }
        }
    }

    fun reportMessage(messageId: String) {
        viewModelScope.launch {
            try {
                messageRepository.reportMessage(messageId)
                _uiState.update { it.copy(error = "Message reported") }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Report failed") }
            }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            try {
                messageRepository.deleteMessage(messageId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Delete failed") }
            }
        }
    }

    fun blockUser(userId: String, username: String) {
        viewModelScope.launch {
            userPreferences.blockUser(userId, username)
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
