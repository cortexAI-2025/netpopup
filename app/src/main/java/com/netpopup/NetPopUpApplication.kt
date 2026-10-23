package com.netpopup

import android.app.Application
import com.netpopup.diagnostics.CrashReport
import com.netpopup.security.SecureWindow
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point — Hilt initializes the DI graph here.
 */
@HiltAndroidApp
class NetPopUpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReport(java.io.File(filesDir, "last_crash.txt")).install()
        // FLAG_SECURE sur toutes les fenêtres, avant leur création.
        registerActivityLifecycleCallbacks(SecureWindow.Callbacks())
    }
}
