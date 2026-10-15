package com.netpopup.ui.screen.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netpopup.data.local.UserPreferences
import com.netpopup.data.model.Room
import com.netpopup.data.push.PushRepository
import com.netpopup.data.repository.AuthRepository
import com.netpopup.data.repository.RoomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoomsUiState(
    val isLoading: Boolean  = false,
    val createdRoom: Room?  = null,
    val joinedRoom: Room?   = null,
    val joinCode: String    = "",
    val error: String?      = null,
    /** true when we just created or joined a room and should navigate */
    val navigateTo: String? = null
)

/**
 * ViewModel for the Private Rooms screen — handles:
 *  - Creating a new private room (auto-generated code).
 *  - Joining an existing room via code.
 *  - Listing rooms already joined, and leaving them (stops their notifications).
 */
@HiltViewModel
class RoomsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val roomRepository: RoomRepository,
    private val userPreferences: UserPreferences,
    private val pushRepository: PushRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RoomsUiState())
    val uiState: StateFlow<RoomsUiState> = _uiState.asStateFlow()

    /** Codes of private rooms the user created or joined on this device. */
    val recentRooms: StateFlow<List<String>> = userPreferences.recentRoomsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onJoinCodeChanged(code: String) {
        _uiState.update { it.copy(joinCode = code.uppercase().take(8)) }
    }

    fun createRoom() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                authRepository.ensureSignedIn()
                val room = roomRepository.createPrivateRoom()
                _uiState.update {
                    it.copy(isLoading = false, createdRoom = room, navigateTo = room.id)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun joinRoom() {
        val code = _uiState.value.joinCode.trim()
        if (code.length < 6) {
            _uiState.update { it.copy(error = "Enter a valid 6-character code") }
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                authRepository.ensureSignedIn()
                val room = roomRepository.joinPrivateRoom(code)
                if (room != null) {
                    _uiState.update {
                        it.copy(isLoading = false, joinedRoom = room, navigateTo = room.id)
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "Room not found") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /** Re-opens a room from the recent list (already validated when first joined). */
    fun openRoom(code: String) = _uiState.update { it.copy(navigateTo = code) }

    fun leaveRoom(code: String) {
        viewModelScope.launch {
            userPreferences.removeRecentRoom(code)
            pushRepository.unsubscribeFromRoom(code)
        }
    }

    fun onNavigated() = _uiState.update { it.copy(navigateTo = null) }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
