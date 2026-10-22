package com.netpopup.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.netpopup.ui.screen.onboarding.OnboardingActions
import com.netpopup.ui.screen.onboarding.OnboardingContent
import com.netpopup.ui.theme.NetPopUpTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OnboardingContentTest {
    @get:Rule val compose = createComposeRule()

    private val reasons = listOf("Bluetooth scan — find nearby phones.", "Notifications — the permanent mesh notification.")

    private fun show(name: String = "Wolf_1234", denied: Boolean = false, initialStep: Int = 0, actions: OnboardingActions = OnboardingActions()) {
        compose.setContent { NetPopUpTheme { OnboardingContent(name, reasons, denied, actions, initialStep) } }
    }

    @Test fun walksThroughModelNamePermissionsThenAsksForThem() {
        var allowed = 0
        show(actions = OnboardingActions(onAllow = { allowed++ }))

        compose.onNodeWithText("Messages from phone to phone").assertIsDisplayed()
        compose.onNodeWithText("The local room is public", substring = true).assertIsDisplayed()   // modèle expliqué, room publique dite
        compose.onNodeWithText("Next").performClick()

        compose.onNodeWithText("Choose a display name").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()

        compose.onNodeWithText("Permissions").assertIsDisplayed()
        compose.onNodeWithText("Bluetooth scan", substring = true).assertIsDisplayed()           // la raison de chaque permission
        compose.onNodeWithText("permanent notification", substring = true).assertIsDisplayed()    // la notification permanente est expliquée
        compose.onNodeWithText("Allow and start").performClick()
        assertEquals(1, allowed)
    }

    @Test fun nameCanBeEditedAndRandomised() {
        var typed = ""
        var randomised = 0
        show(initialStep = 1, actions = OnboardingActions(onNameChange = { typed = it }, onRandomName = { randomised++ }))
        compose.onNodeWithText("Wolf_1234").performTextReplacement("Alice")
        assertEquals("Alice", typed)
        compose.onNodeWithText("Random name").performClick()
        assertEquals(1, randomised)
    }

    @Test fun blankNameBlocksProgress() {
        show(name = "  ", initialStep = 1)
        compose.onNodeWithText("Next").assertIsNotEnabled()
    }

    @Test fun refusalLeavesTheAppUsableAndExplainsHowToReEnable() {
        var without = 0
        var retried = 0
        var openedSettings = 0
        show(denied = true, initialStep = 2, actions = OnboardingActions(
            onAllow = { retried++ }, onOpenAndroidSettings = { openedSettings++ }, onContinueWithoutMesh = { without++ },
        ))
        compose.onNodeWithText("Some permissions were refused", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Open Android settings").performClick()
        compose.onNodeWithText("Continue without the mesh").performClick()
        assertEquals(listOf(1, 1, 1), listOf(retried, openedSettings, without))
    }
}


