package com.netpopup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        enableEdgeToEdge()
        setContent {
            NetPopUpTheme {
                NavGraph()
            }
        }
    }
}
