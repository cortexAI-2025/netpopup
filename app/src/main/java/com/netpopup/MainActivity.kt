package com.netpopup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.os.Build
import com.netpopup.diagnostics.CrashReport
import com.netpopup.diagnostics.CrashReportDialog
import com.netpopup.mesh.MeshPermissions
import com.netpopup.mesh.MessageNotifier
import com.netpopup.settings.AppSettings
import javax.inject.Inject
import com.netpopup.mesh.MeshRuntime
import com.netpopup.mesh.MeshService
import com.netpopup.security.SecureWindow
import com.netpopup.ui.navigation.NavGraph
import com.netpopup.ui.theme.NetPopUpTheme
import com.netpopup.ui.screen.onboarding.OnboardingScreen
import com.netpopup.ui.screen.startup.StartupScreen
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    @Inject lateinit var runtime: MeshRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Déjà posé par NetPopUpApplication avant onCreate ; répété ici pour que l'intention reste lisible.
        SecureWindow.enable(window)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge()

        val crash = CrashReport(java.io.File(filesDir, "last_crash.txt"))
        // Un plantage en attente : ni service ni navigation (qui rouvrent la base) avant que la trace ait pu être copiée,
        // sinon un plantage de démarrage reproductible referme l'app avant qu'on la lise.

        setContent {
            NetPopUpTheme {
                var crashText by remember { mutableStateOf(crash.pending()) }
                crashText?.let { text -> CrashReportDialog(text) { crash.clear(); crashText = null } }
                val currentSettings by settings.state.collectAsState()
                var runtimeReady by remember { mutableStateOf(false) }
                if (crashText == null) {
                    when {
                        !currentSettings.onboardingDone -> OnboardingScreen(onDone = {})
                        !runtimeReady -> StartupScreen(prepare = runtime::prepare, onReady = {
                            if (settings.snapshot.meshEnabled && MeshPermissions.missing(this).isEmpty()) MeshService.start(this)
                            runtimeReady = true
                        })
                        else -> NavGraph()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        MessageNotifier(this).clear() // l'utilisateur est là : plus rien à signaler
    }
}
