package com.netpopup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.os.Build
import com.netpopup.security.SecureWindow
import com.netpopup.ui.navigation.NavGraph
import com.netpopup.ui.theme.NetPopUpTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host. All navigation is handled by Jetpack Navigation Compose.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Déjà posé par NetPopUpApplication avant onCreate ; répété ici pour que l'intention reste lisible.
        SecureWindow.enable(window)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge()
        setContent {
            NetPopUpTheme {
                NavGraph()
            }
        }
    }
}
