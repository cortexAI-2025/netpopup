package com.netpopup.settings

import android.content.Context
import com.netpopup.dtn.presentation.LifetimeChoices
import com.netpopup.util.UsernameGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class SettingsSnapshot(
    val onboardingDone: Boolean,
    val displayName: String,
    val meshEnabled: Boolean,
    val notificationsEnabled: Boolean,
    val lifetimeHours: Int,
)

/**
 * Préférences d'affichage et de comportement, **non sensibles** (SharedPreferences non chiffrées) :
 * rien de ce qui touche aux messages, aux contacts ou aux clés n'y est stocké.
 */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {
    // Le pseudo reste dans le fichier historique pour ne pas changer d'identité à la mise à jour.
    private val identityPrefs = context.getSharedPreferences("netpopup_identity", Context.MODE_PRIVATE)
    private val prefs = context.getSharedPreferences("netpopup_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        SettingsSnapshot(
            onboardingDone = prefs.getBoolean(ONBOARDING_DONE, false),
            displayName = identityPrefs.getString(DISPLAY_NAME, null) ?: UsernameGenerator.generate().also {
                identityPrefs.edit().putString(DISPLAY_NAME, it).apply()
            },
            meshEnabled = prefs.getBoolean(MESH_ENABLED, true),
            notificationsEnabled = prefs.getBoolean(NOTIFICATIONS, true),
            lifetimeHours = LifetimeChoices.normalize(prefs.getInt(LIFETIME_HOURS, LifetimeChoices.DEFAULT_HOURS)),
        )
    )
    val state: StateFlow<SettingsSnapshot> = _state.asStateFlow()

    val snapshot: SettingsSnapshot get() = _state.value

    fun setOnboardingDone(done: Boolean) {
        prefs.edit().putBoolean(ONBOARDING_DONE, done).apply()
        _state.update { it.copy(onboardingDone = done) }
    }

    /** Un pseudo vide est refusé (on garde l'ancien) ; 32 caractères au plus. */
    fun setDisplayName(name: String) {
        val clean = name.trim().take(MAX_NAME)
        if (clean.isEmpty()) return
        identityPrefs.edit().putString(DISPLAY_NAME, clean).apply()
        _state.update { it.copy(displayName = clean) }
    }

    fun generateDisplayName() = setDisplayName(UsernameGenerator.generate())

    fun setMeshEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(MESH_ENABLED, enabled).apply()
        _state.update { it.copy(meshEnabled = enabled) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(NOTIFICATIONS, enabled).apply()
        _state.update { it.copy(notificationsEnabled = enabled) }
    }

    fun setLifetimeHours(hours: Int) {
        val h = LifetimeChoices.normalize(hours)
        prefs.edit().putInt(LIFETIME_HOURS, h).apply()
        _state.update { it.copy(lifetimeHours = h) }
    }

    private companion object {
        const val ONBOARDING_DONE = "onboarding_done"
        const val DISPLAY_NAME = "display_name"
        const val MESH_ENABLED = "mesh_enabled"
        const val NOTIFICATIONS = "notifications_enabled"
        const val LIFETIME_HOURS = "lifetime_hours"
        const val MAX_NAME = 32
    }
}
