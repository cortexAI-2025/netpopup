package com.netpopup.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.netpopup.ui.screen.startup.StartupScreen
import com.netpopup.ui.theme.NetPopUpTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w420dp-h900dp")
class StartupScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedNativeInitializationShowsRecoveryAndRetryCanSucceed() {
        var attempts = 0
        var opened = 0
        compose.setContent {
            NetPopUpTheme {
                StartupScreen(prepare = {
                    if (++attempts == 1) throw UnsatisfiedLinkError("missing native library")
                }, onReady = { opened++ })
            }
        }
        compose.onNodeWithText("Unable to open NetPopUp").assertIsDisplayed()
        compose.onNodeWithText("Copy diagnostic").assertIsDisplayed()
        assertEquals(0, opened)
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertEquals(2, attempts)
        assertEquals(1, opened)
    }
}
