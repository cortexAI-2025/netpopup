package com.bitchat.android

import android.app.Application
import com.bitchat.android.ui.theme.ThemePreferenceManager

/**
 * Main application class for bitchat Android
 */
class BitchatApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // NetPopUp V2 : trace du dernier plantage gardée en local (affichée au lancement suivant, voir MainActivity)
        val crash = com.netpopup.v2.diagnostics.CrashReport(java.io.File(filesDir, "last_crash.txt")) {
            "NetPopUp ${com.bitchat.android.BuildConfig.VERSION_NAME} (${com.bitchat.android.BuildConfig.BUILD_TYPE}) " +
                "| ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} | Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
        }
        crash.install()
        val crashPending = crash.pending() != null

        // NetPopUp V2 : salons chiffrés (clés dans le Keystore)
        try { com.netpopup.v2.rooms.RoomRepository.init(this) } catch (_: Exception) { }

        // Start the single process-wide power policy before transport components are constructed.
        com.bitchat.android.mesh.PowerManager.getInstance(this).start()

        // NetPopUp V2 : aucun accès réseau. Tor, annuaire de relais Nostr et connexion aux relais ne sont pas démarrés
        // (le code reste dans l'arbre, inactif, et la permission INTERNET est retirée du manifeste).

        // Initialize LocationNotesManager dependencies early so sheet subscriptions can start immediately
        try { com.bitchat.android.nostr.LocationNotesInitializer.initialize(this) } catch (_: Exception) { }

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        try {
            com.bitchat.android.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Restore private conversations before background transports can deliver new messages.
        // AppStateStore merges any in-flight arrivals by message ID, so startup cannot replace
        // newer transport state with an older database snapshot.
        try {
            com.bitchat.android.services.AppStateStore.initializeConversationPersistence(this)
        } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Restore the spam filter toggle before transports start admitting public messages
        com.bitchat.android.spam.SpamFilterPreferences.init(this)

        // Initialize theme preference
        ThemePreferenceManager.init(this)

        // Initialize chat UI mode (matrix transcript vs bubbles)
        com.bitchat.android.ui.theme.ChatUiModeManager.init(this)

        // Initialize debug preference manager (persists debug toggles)
        try { com.bitchat.android.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // Initialize Wi‑Fi Aware controller with persisted default
        try {
            val enabled = com.bitchat.android.ui.debug.DebugPreferenceManager.getWifiAwareEnabled(false)
            com.bitchat.android.wifiaware.WifiAwareController.initialize(this, enabled)
        } catch (_: Exception) { }

        // Initialize Geohash Registries for persistence
        try {
            com.bitchat.android.nostr.GeohashAliasRegistry.initialize(this)
            com.bitchat.android.nostr.GeohashConversationRegistry.initialize(this)
        } catch (_: Exception) { }


        // Initialize mesh service preferences
        try { com.bitchat.android.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // Proactively start the foreground service to keep mesh alive
        // Un plantage en attente : pas de démarrage automatique du service tant que la trace n'a pas pu être lue.
        if (!crashPending) { try { com.bitchat.android.service.MeshForegroundService.start(this) } catch (_: Exception) { } }

        // TorManager already initialized above
    }
}
