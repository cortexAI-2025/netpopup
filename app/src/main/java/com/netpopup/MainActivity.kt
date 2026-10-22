package com.netpopup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.os.Build
import com.netpopup.mesh.MeshPermissions
import com.netpopup.mesh.MessageNotifier
import com.netpopup.settings.AppSettings
import com.netpopup.ui.navigation.Screen
import javax.inject.Inject
import com.netpopup.mesh.MeshService
import com.netpopup.security.SecureWindow
import com.netpopup.ui.navigation.NavGraph
import com.netpopup.ui.theme.NetPopUpTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host. All navigation is handled by Jetpack Navigation Compose.
 *
 * Premier lancement : l'onboarding (modèle, pseudo, permissions). Ensuite, démarre le service du mesh si les
 * permissions sont accordées et que l'utilisateur ne l'a pas coupé dans les réglages.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Déjà posé par NetPopUpApplication avant onCreate ; répété ici pour que l'intention reste lisible.
        SecureWindow.enable(window)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge()

        val onboarded = settings.snapshot.onboardingDone
        // Après l'onboarding, les permissions ne sont plus demandées ici : les réglages s'en chargent.
        if (onboarded && settings.snapshot.meshEnabled && MeshPermissions.missing(this).isEmpty()) MeshService.start(this)

        setContent {
            NetPopUpTheme {
                NavGraph(startDestination = if (onboarded) Screen.LocalChat.route else Screen.Onboarding.route)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        MessageNotifier(this).clear() // l'utilisateur est là : plus rien à signaler
    }
}
