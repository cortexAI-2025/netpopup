package com.netpopup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import com.netpopup.mesh.MeshPermissions
import com.netpopup.mesh.MeshService
import com.netpopup.security.SecureWindow
import com.netpopup.ui.navigation.NavGraph
import com.netpopup.ui.theme.NetPopUpTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host. All navigation is handled by Jetpack Navigation Compose.
 *
 * Provisoire : demande les permissions du mesh puis lance son service au démarrage.
 * L'onboarding (mesh-16, #17) prendra le relais, avec les explications qui manquent ici.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val meshPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (MeshPermissions.missing(this).isEmpty()) MeshService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Déjà posé par NetPopUpApplication avant onCreate ; répété ici pour que l'intention reste lisible.
        SecureWindow.enable(window)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge()
        val toRequest = MeshPermissions.toRequest(this)
        if (toRequest.isEmpty()) MeshService.start(this) else meshPermissions.launch(toRequest.toTypedArray())
        setContent {
            NetPopUpTheme {
                NavGraph()
            }
        }
    }
}
