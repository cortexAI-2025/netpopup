package com.netpopup.ui.screen.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import com.netpopup.mesh.MeshPermissions
import com.netpopup.mesh.MeshService
import com.netpopup.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

// @ApplicationContext : le contexte d'application vit autant que le processus, il ne peut pas fuir une activité.
@Suppress("StaticFieldLeak")
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: AppSettings,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val displayName: StateFlow<String> get() = nameFlow
    private val nameFlow = kotlinx.coroutines.flow.MutableStateFlow(settings.snapshot.displayName)

    fun setName(name: String) {
        nameFlow.value = name.take(32)
    }

    fun randomName() {
        settings.generateDisplayName()
        nameFlow.value = settings.snapshot.displayName
    }

    /**
     * Termine l'onboarding (une seule fois) : enregistre le pseudo, démarre le service si les permissions sont là.
     * Refusées, l'app reste utilisable en lecture et les réglages permettent de les redemander.
     */
    fun finish() {
        settings.setDisplayName(nameFlow.value)
        settings.setOnboardingDone(true)
        if (settings.snapshot.meshEnabled && MeshPermissions.missing(context).isEmpty()) MeshService.start(context)
    }
}
