package com.netpopup.mesh

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Permissions du **manifeste fusionné** (application + bibliothèques) : c'est lui qui part dans l'APK.
 * L'application ne doit pas pouvoir accéder à Internet ; elle doit déclarer ce que Nearby exige.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ManifestPermissionsTest {
    /** Vue d'Android (peut écarter les permissions dont `maxSdkVersion` est inférieur à la version émulée). */
    private val viaPackageManager: Set<String> by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
    }

    /** Le fichier de manifeste fusionné lui-même, sans filtre de version. */
    private val declared: Set<String> by lazy {
        val manifests = java.io.File("build/intermediates").walkTopDown()
            .filter { it.name == "AndroidManifest.xml" && "merged_manifest" in it.path && "debug" in it.path && "Test" !in it.path }
            .toList()
        assertTrue("manifeste fusionné introuvable sous build/intermediates", manifests.isNotEmpty())
        val names = Regex("""<uses-permission(?:-sdk-23)?[^>]*?android:name="([^"]+)"""")
        manifests.flatMap { f -> names.findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet() + viaPackageManager
    }

    @Test fun thereIsNoInternetPermission() {
        assertFalse("INTERNET déclarée (fichier) : $declared", "android.permission.INTERNET" in declared)
        assertFalse("INTERNET déclarée (PackageManager) : $viaPackageManager", "android.permission.INTERNET" in viaPackageManager)
    }

    @Test fun nearbyPermissionsAreDeclared() {
        for (p in listOf(
            "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT",
            "android.permission.NEARBY_WIFI_DEVICES", "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
        )) assertTrue("$p manquante : $declared", p in declared)
    }

    @Test fun foregroundServicePermissionsAreDeclared() {
        assertTrue("android.permission.FOREGROUND_SERVICE" in declared)
        assertTrue("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" in declared)
        assertTrue("android.permission.POST_NOTIFICATIONS" in declared)
    }

    @Test fun everyRuntimePermissionOfEveryAndroidVersionIsDeclared() {
        // Ce que PermissionPolicy demandera sur n'importe quelle version doit exister dans le manifeste.
        for (sdk in 29..34) {
            for (p in com.netpopup.transport.nearby.PermissionPolicy.allRequested(sdk)) {
                assertTrue("$p (API $sdk) absente du manifeste : $declared", p in declared)
            }
        }
    }

    @Test fun permissionSetMatchesTheAndroidVersion() {
        val policy = com.netpopup.transport.nearby.PermissionPolicy
        assertTrue(policy.runtimePermissions(29) == listOf(policy.FINE_LOCATION))
        assertTrue(policy.runtimePermissions(30) == listOf(policy.FINE_LOCATION))
        assertTrue(policy.FINE_LOCATION in policy.runtimePermissions(31) && policy.COARSE_LOCATION in policy.runtimePermissions(31) && policy.BLUETOOTH_SCAN in policy.runtimePermissions(31))
        assertFalse(policy.FINE_LOCATION in policy.runtimePermissions(32))
        assertTrue(policy.NEARBY_WIFI_DEVICES in policy.runtimePermissions(33))
        assertTrue(policy.POST_NOTIFICATIONS in policy.allRequested(34))
        assertFalse(policy.POST_NOTIFICATIONS in policy.allRequested(32))
    }
}
