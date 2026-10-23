package com.netpopup.mesh

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.netpopup.transport.nearby.PermissionPolicy

/** Pont entre [PermissionPolicy] (pur) et les permissions réellement accordées sur l'appareil. */
object MeshPermissions {

    /** Tout ce qu'il faut demander à l'utilisateur, Nearby et notification. */
    fun toRequest(context: Context): List<String> =
        PermissionPolicy.allRequested(Build.VERSION.SDK_INT).filterNot { isGranted(context, it) }

    /** Toutes les permissions que le mesh utilise sur cette version d'Android, accordées ou non. */
    fun allRequested(context: Context): List<String> = PermissionPolicy.allRequested(Build.VERSION.SDK_INT)

    /** Ce qui manque pour que Nearby accepte de démarrer (la notification n'est pas exigée). */
    fun missing(context: Context): List<String> =
        PermissionPolicy.missing(Build.VERSION.SDK_INT, granted(context))

    private fun granted(context: Context): Set<String> =
        PermissionPolicy.allRequested(Build.VERSION.SDK_INT).filter { isGranted(context, it) }.toSet()

    private fun isGranted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
