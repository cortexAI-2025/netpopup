package com.netpopup.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.netpopup.ui.component.NetPopUpTopBar
import com.netpopup.ui.theme.NetPopUpTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TopBarStatusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun brandedHeaderStillDisplaysTheMeshStatus() {
        compose.setContent {
            NetPopUpTheme { NetPopUpTopBar(showBrandLogo = true, subtitle = "Public room · Searching nearby") }
        }
        compose.onNodeWithText("Public room · Searching nearby").assertIsDisplayed()
    }
}
