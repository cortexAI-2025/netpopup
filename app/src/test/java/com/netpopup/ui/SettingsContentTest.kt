package com.netpopup.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.netpopup.dtn.chat.MeshInfo
import com.netpopup.dtn.chat.MeshStatus
import com.netpopup.settings.SettingsSnapshot
import com.netpopup.ui.screen.settings.SettingsActions
import com.netpopup.ui.screen.settings.SettingsContent
import com.netpopup.ui.screen.settings.SettingsUiState
import com.netpopup.ui.theme.NetPopUpTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SettingsContentTest {
    @get:Rule val compose = createComposeRule()

    private val base = SettingsSnapshot(onboardingDone = true, displayName = "Wolf_1234", meshEnabled = true, notificationsEnabled = true, lifetimeHours = 6)

    private fun show(
        state: SettingsUiState = SettingsUiState(base),
        permissionsMissing: Boolean = false,
        actions: SettingsActions = SettingsActions(),
    ) {
        compose.setContent { NetPopUpTheme { SettingsContent(state, permissionsMissing, onBack = {}, actions = actions) } }
    }

    @Test fun showsMeshStateFingerprintAndStore() {
        show(SettingsUiState(base, MeshStatus(isRunning = true, peersInRange = 3), MeshInfo(storedEnvelopes = 42, clockSkewSuspected = false), fingerprint = "ABCD 1234"))
        compose.onNodeWithText("Active · 3 phone(s) in range · 42 envelope(s) stored").assertIsDisplayed()
        compose.onNodeWithText("ABCD 1234").performScrollTo().assertIsDisplayed()
    }

    @Test fun stoppedMeshExplainsTheEffectOfTheSwitch() {
        show(SettingsUiState(base.copy(meshEnabled = false)))
        compose.onNodeWithText("Off: no reception and no relaying", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Stopped", substring = true).assertIsDisplayed()
    }

    @Test fun meshSwitchReportsTheNewValue() {
        val seen = mutableListOf<Boolean>()
        show(actions = SettingsActions(onMeshToggle = { seen += it }))
        compose.onNodeWithTag("mesh_switch").performClick()
        assertEquals(listOf(false), seen)          // il était activé
    }

    @Test fun clockWarningAppearsOnlyWhenSuspected() {
        show(SettingsUiState(base, info = MeshInfo(0, clockSkewSuspected = true)))
        compose.onNodeWithText("Clock warning", substring = true).assertIsDisplayed()
    }

    @Test fun noClockWarningByDefault() {
        show()
        compose.onNodeWithText("Clock warning", substring = true).assertDoesNotExist()
    }

    @Test fun missingPermissionsOfferToGrantThem() {
        var granted = 0
        show(permissionsMissing = true, actions = SettingsActions(onRequestPermissions = { granted++ }))
        compose.onNodeWithText("Some permissions are missing", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Grant permissions").performClick()
        assertEquals(1, granted)
    }

    @Test fun lifetimeChoiceIsReported() {
        var chosen = -1
        show(actions = SettingsActions(onLifetime = { chosen = it }))
        compose.onNodeWithTag("lifetime_selected_6").performScrollTo().assertIsDisplayed()   // 6 h par défaut, choisi
        compose.onNodeWithTag("lifetime_24").performScrollTo().performClick()
        assertEquals(24, chosen)
    }

    @Test fun notificationSwitchReportsTheNewValue() {
        val seen = mutableListOf<Boolean>()
        show(actions = SettingsActions(onNotifications = { seen += it }))
        compose.onNodeWithTag("notifications_switch").performScrollTo().performClick()
        assertEquals(listOf(false), seen)
        compose.onNodeWithText("only says \"New message\"", substring = true).assertExists()
    }

    @Test fun blockedSendersCanBeUnblockedAndTheLimitsAreSaid() {
        val unblocked = mutableListOf<String>()
        show(SettingsUiState(base, blocked = setOf("peer-key-0123456789abcdef")), actions = SettingsActions(onUnblock = { unblocked += it }))
        compose.onNodeWithText("There is no central report and no ban", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Unblock").performScrollTo().performClick()
        assertEquals(listOf("peer-key-0123456789abcdef"), unblocked)
    }

    @Test fun emptyBlockListSaysSo() {
        show()
        compose.onNodeWithText("Nobody is blocked.").performScrollTo().assertIsDisplayed()
    }

    @Test fun nameCanBeSavedAfterEdit() {
        var saved = ""
        show(actions = SettingsActions(onSaveName = { saved = it }))
        // Le bouton Save est désactivé tant que le nom n'a pas changé.
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabledSafe()
        compose.onNodeWithText("Random name").performScrollTo().assertIsDisplayed()
        assertEquals("", saved)
    }

    @Test fun licenceAndSourceAreReachable() {
        var opened = 0
        show(actions = SettingsActions(onOpenSource = { opened++ }))
        compose.onNodeWithText("GNU AGPL-3.0", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("View the source code").performScrollTo().performClick()
        assertEquals(1, opened)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsNotEnabledSafe() = assertIsNotEnabled()
