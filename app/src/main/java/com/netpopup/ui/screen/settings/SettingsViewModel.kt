package com.netpopup.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netpopup.dtn.chat.ChatRepository
import com.netpopup.dtn.chat.MeshInfo
import com.netpopup.dtn.chat.MeshStatus
import com.netpopup.mesh.MeshRuntime
import com.netpopup.mesh.MeshService
import com.netpopup.transport.nearby.NearbyDiagnostics
import com.netpopup.settings.AppSettings
import com.netpopup.settings.SettingsSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: SettingsSnapshot,
    val status: MeshStatus = MeshStatus.STOPPED,
    val info: MeshInfo = MeshInfo.EMPTY,
    val blocked: Set<String> = emptySet(),
    val fingerprint: String = "",
    val nearby: NearbyDiagnostics = NearbyDiagnostics(),
)

// @ApplicationContext : le contexte d'application vit autant que le processus, il ne peut pas fuir une activité.
@Suppress("StaticFieldLeak")
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val runtime: MeshRuntime,
    private val settings: AppSettings,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val fingerprint = MutableStateFlow("")

    init {
        viewModelScope.launch { fingerprint.value = runCatching { repository.myFingerprint() }.getOrDefault("") }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.state, repository.meshStatus, repository.observeMeshInfo(), repository.observeBlocked(), fingerprint,
        runtime.nearbyDiagnostics,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        SettingsUiState(
            settings = values[0] as SettingsSnapshot,
            status = values[1] as MeshStatus,
            info = values[2] as MeshInfo,
            blocked = values[3] as Set<String>,
            fingerprint = values[4] as String,
            nearby = values[5] as NearbyDiagnostics,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(settings.snapshot))

    fun setDisplayName(name: String) = settings.setDisplayName(name)
    fun generateDisplayName() = settings.generateDisplayName()
    fun setLifetimeHours(hours: Int) = settings.setLifetimeHours(hours)
    fun setNotificationsEnabled(enabled: Boolean) = settings.setNotificationsEnabled(enabled)
    fun unblock(senderKey: String) { viewModelScope.launch { repository.unblock(senderKey) } }

    /** Le service ne démarre qu'avec les permissions : l'écran les demande avant d'appeler avec `true`. */
    fun setMeshEnabled(enabled: Boolean) {
        settings.setMeshEnabled(enabled)
        if (enabled) MeshService.start(context) else MeshService.stop(context)
    }
}
