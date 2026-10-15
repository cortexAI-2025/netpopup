package com.netpopup.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netpopup.data.local.BlockedUser
import com.netpopup.data.local.UserPreferences
import com.netpopup.util.UsernameGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val username: String                = "",
    val notificationsEnabled: Boolean   = true,
    val blockedUsers: List<BlockedUser> = emptyList()
)

/**
 * ViewModel for the Settings screen — everything here is local (DataStore):
 *  - regenerate the anonymous username,
 *  - toggle push notifications,
 *  - review and unblock blocked users.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferences: UserPreferences
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        userPreferences.usernameFlow,
        userPreferences.notificationsEnabledFlow,
        userPreferences.blockedUsersDetailedFlow
    ) { username, notifications, blocked ->
        SettingsUiState(
            username             = username.orEmpty(),
            notificationsEnabled = notifications,
            blockedUsers         = blocked
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun regenerateUsername() {
        viewModelScope.launch { userPreferences.saveUsername(UsernameGenerator.generate()) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferences.setNotificationsEnabled(enabled) }
    }

    fun unblock(userId: String) {
        viewModelScope.launch { userPreferences.unblockUser(userId) }
    }
}
