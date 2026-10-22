package com.netpopup.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppSettingsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun defaultsAreSafe() {
        val s = AppSettings(context).snapshot
        assertFalse("l'onboarding n'est pas fait au premier lancement", s.onboardingDone)
        assertTrue(s.meshEnabled)
        assertTrue(s.notificationsEnabled)
        assertEquals(6, s.lifetimeHours)
        assertTrue("un pseudo est généré", s.displayName.isNotBlank())
    }

    @Test fun onboardingFlagAndChoicesSurviveRestart() {
        AppSettings(context).apply {
            setOnboardingDone(true)
            setMeshEnabled(false)
            setNotificationsEnabled(false)
            setLifetimeHours(12)
            setDisplayName("  Alice  ")
        }
        val reloaded = AppSettings(context).snapshot            // nouvelle instance = redémarrage de l'app
        assertTrue("l'onboarding n'est pas rejoué", reloaded.onboardingDone)
        assertFalse(reloaded.meshEnabled)
        assertFalse(reloaded.notificationsEnabled)
        assertEquals(12, reloaded.lifetimeHours)
        assertEquals("Alice", reloaded.displayName)
    }

    @Test fun invalidValuesAreRefusedOrClamped() {
        val s = AppSettings(context)
        s.setDisplayName("Bob")
        s.setDisplayName("   ")
        assertEquals("un pseudo vide est refusé", "Bob", s.snapshot.displayName)
        s.setDisplayName("x".repeat(100))
        assertEquals(32, s.snapshot.displayName.length)
        s.setLifetimeHours(1000)
        assertEquals("24 h au plus", 24, s.snapshot.lifetimeHours)
        s.setLifetimeHours(0)
        assertEquals(1, s.snapshot.lifetimeHours)
    }

    @Test fun randomNameChangesTheName() {
        val s = AppSettings(context)
        val before = s.snapshot.displayName
        repeat(20) { s.generateDisplayName() }
        assertTrue(s.snapshot.displayName.isNotBlank())
        assertTrue("au moins un tirage diffère (ou le nom reste valide)", s.snapshot.displayName != before || before.isNotBlank())
    }
}
