package com.netpopup.security

import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * À lancer sur un appareil (`connectedDebugAndroidTest`) : la CI ne les exécute pas.
 * La capture d'écran réelle se vérifie à la main : voir docs/flag-secure-manual-test.md.
 */
@RunWith(AndroidJUnit4::class)
class SecureWindowInstrumentedTest {

    /** Une activité quelconque, qui n'a rien fait elle-même : le drapeau vient de l'Application. */
    @Test fun everyActivityGetsFlagSecureBeforeItsContent() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertTrue(SecureWindow.isEnabled(activity.window)) }
        }
    }
}
