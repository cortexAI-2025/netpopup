package com.netpopup.startup

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netpopup.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Hilt graph, native SQLCipher, Android Keystore and libsignal; no mock repository. */
@RunWith(AndroidJUnit4::class)
class StartupJourneyInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun onboardingNamePermissionsOfflineSendAndActivityRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("netpopup_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.filesDir.resolve("last_crash.txt").delete()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("Next").performScrollTo().performClick()
            compose.onNodeWithText("Display name").performTextReplacement("Ahmed")
            compose.onNodeWithText("Next").performScrollTo().performClick()
            compose.onNodeWithText("Permissions", substring = false).assertIsDisplayed()
            // Keep the exact step that crashed on the phone across activity recreation.
            scenario.recreate()
            compose.onNodeWithText("Permissions", substring = false).assertIsDisplayed()
            compose.onNodeWithText("Continue without the mesh").performScrollTo().performClick()
            compose.waitUntil(90_000) {
                compose.onAllNodesWithText("No messages yet.", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Message…").performTextInput("Offline smoke test")
            compose.onNodeWithContentDescription("Send").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Offline smoke test").fetchSemanticsNodes().isNotEmpty()
            }
            scenario.recreate()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Offline smoke test").fetchSemanticsNodes().isNotEmpty()
            }
            assertFalse(context.getSharedPreferences("netpopup_settings", Context.MODE_PRIVATE).getBoolean("mesh_enabled", true))
        }
    }
}
