package com.netpopup.ui.screen.onboarding

import androidx.lifecycle.ViewModel
import com.netpopup.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: AppSettings,
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
     * Termine l'onboarding (une seule fois) : enregistre le pseudo. Le moteur démarre ensuite dans l’écran de préparation.
     * Refusées, l'app reste utilisable en lecture et les réglages permettent de les redemander.
     */
    fun finish(enableMesh: Boolean = true) {
        settings.setDisplayName(nameFlow.value)
        settings.setMeshEnabled(enableMesh)
        settings.setOnboardingDone(true)
    }
}
