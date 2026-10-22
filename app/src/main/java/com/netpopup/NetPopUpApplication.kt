package com.netpopup

import android.app.Application
import com.netpopup.security.SecureWindow
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point — Hilt initializes the DI graph here.
 * Firebase is auto-initialized via google-services.json at build time.
 */
@HiltAndroidApp
class NetPopUpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // FLAG_SECURE sur toutes les fenêtres, avant leur création.
        registerActivityLifecycleCallbacks(SecureWindow.Callbacks())
    }
}
