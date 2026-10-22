package com.netpopup.ui.screen.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.netpopup.mesh.MeshPermissions
import com.netpopup.ui.theme.Error
import com.netpopup.ui.theme.Primary
import com.netpopup.ui.theme.TextPrimary
import com.netpopup.ui.theme.TextSecondary

/** Raison, en clair, de chaque permission demandée. */
private fun reasonFor(permission: String): String? = when (permission) {
    Manifest.permission.ACCESS_FINE_LOCATION -> "Location — Android requires it to scan for nearby phones over Bluetooth. Your position is never stored or sent."
    Manifest.permission.ACCESS_COARSE_LOCATION -> "Approximate location — Android 12 asks for it together with precise location. Choose \"Precise\" when prompted."
    Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth scan — find nearby phones."
    Manifest.permission.BLUETOOTH_ADVERTISE -> "Bluetooth advertise — let nearby phones find you."
    Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth connect — exchange messages with a nearby phone."
    "android.permission.NEARBY_WIFI_DEVICES" -> "Nearby Wi-Fi devices — switch to a faster link once connected."
    Manifest.permission.POST_NOTIFICATIONS -> "Notifications — the permanent mesh notification and \"New message\" alerts."
    else -> null
}

/** Actions de l'onboarding, séparées de l'écran pour pouvoir le tester sans Hilt ni permissions réelles. */
class OnboardingActions(
    val onNameChange: (String) -> Unit = {},
    val onRandomName: () -> Unit = {},
    /** Demande les permissions manquantes (ou termine si tout est déjà accordé). */
    val onAllow: () -> Unit = {},
    val onOpenAndroidSettings: () -> Unit = {},
    /** Termine sans le mesh : l'app reste utilisable en lecture. */
    val onContinueWithoutMesh: () -> Unit = {},
)

/**
 * Premier lancement : le modèle en quelques phrases, le pseudo, puis les permissions avec leurs raisons.
 * Terminé une fois, il ne revient plus.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val name by viewModel.displayName.collectAsState()
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (MeshPermissions.missing(context).isEmpty()) { viewModel.finish(); onDone() } else denied = true
    }

    OnboardingContent(
        name = name,
        permissionReasons = MeshPermissions.allRequested(context).mapNotNull { reasonFor(it) },
        denied = denied,
        actions = OnboardingActions(
            onNameChange = viewModel::setName,
            onRandomName = viewModel::randomName,
            onAllow = {
                val toRequest = MeshPermissions.toRequest(context)
                if (toRequest.isEmpty()) { viewModel.finish(); onDone() } else launcher.launch(toRequest.toTypedArray())
            },
            onOpenAndroidSettings = {
                context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            },
            onContinueWithoutMesh = { viewModel.finish(); onDone() },
        ),
    )
}

@Composable
fun OnboardingContent(
    name: String,
    permissionReasons: List<String>,
    denied: Boolean,
    actions: OnboardingActions,
    initialStep: Int = 0,
) {
    var step by remember { mutableIntStateOf(initialStep) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("NetPopUp", color = Primary, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        when (step) {
            0 -> {
                Text("Messages from phone to phone", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Text("• No account, no server, no Internet: nearby phones pass messages along.", color = TextSecondary)
                Text("• Messages are ephemeral: they disappear after a few hours.", color = TextSecondary)
                Text("• Delivery is not instant: a message leaves when a phone that can carry it comes within range.", color = TextSecondary)
                Text("• The local room is public: anyone in range can read it. Private rooms and contacts are encrypted.", color = TextSecondary)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                    Text("Next", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
            1 -> {
                Text("Choose a display name", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Text("Your name is shown to the people you talk to. It is not an account and you can change it any time.", color = TextSecondary)
                Text("Your cryptographic identity is created on this phone only; it never leaves it.", color = TextSecondary)
                OutlinedTextField(value = name, onValueChange = actions.onNameChange, singleLine = true, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = actions.onRandomName) { Text("Random name") }
                Button(onClick = { step = 2 }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                    Text("Next", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
            else -> {
                Text("Permissions", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Text("To find and talk to nearby phones, Android needs your permission:", color = TextSecondary)
                permissionReasons.forEach { Text("• $it", color = TextSecondary) }
                Text(
                    "While the mesh runs, a permanent notification stays visible. Android requires it to keep Bluetooth active in the background; " +
                        "it never shows any message.",
                    color = TextSecondary
                )
                if (denied) {
                    Text(
                        "Some permissions were refused. You can read the app, but nothing will be sent or received until you allow them.",
                        color = Error
                    )
                    OutlinedButton(onClick = actions.onAllow, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                    TextButton(onClick = actions.onOpenAndroidSettings) { Text("Open Android settings") }
                    Button(onClick = actions.onContinueWithoutMesh, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                        Text("Continue without the mesh", color = MaterialTheme.colorScheme.onPrimary)
                    }
                } else {
                    Button(onClick = actions.onAllow, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                        Text("Allow and start", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}
