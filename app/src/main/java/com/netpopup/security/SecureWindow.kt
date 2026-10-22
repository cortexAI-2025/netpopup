package com.netpopup.security

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Window
import android.view.WindowManager

/**
 * Empêche captures d'écran, enregistrement d'écran et aperçu dans les applications récentes
 * (architecture mesh v0.1, section 5). Les messages et les listes de contacts ne doivent jamais
 * sortir de l'écran par un chemin que l'utilisateur ne voit pas.
 *
 * Les dialogues et menus Compose sont des fenêtres séparées : ils héritent du drapeau de l'activité
 * (`SecureFlagPolicy.Inherit`, le réglage par défaut), à condition qu'il soit posé **avant** leur création.
 * [Callbacks] le pose avant `onCreate` de chaque activité, quelle qu'elle soit.
 */
object SecureWindow {

    fun enable(window: Window) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun isEnabled(window: Window): Boolean =
        window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    /** À enregistrer dans `Application.onCreate` : couvre aussi les activités ajoutées plus tard. */
    class Callbacks : Application.ActivityLifecycleCallbacks {
        // Appelé avant Activity.onCreate (API 29+, soit notre minSdk) : donc avant tout setContent.
        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) = enable(activity.window)

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = enable(activity.window)
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
