package com.netpopup.transport.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PermissionPolicyTest {

    private val bluetooth = listOf(
        PermissionPolicy.BLUETOOTH_SCAN, PermissionPolicy.BLUETOOTH_ADVERTISE, PermissionPolicy.BLUETOOTH_CONNECT
    )

    @Test
    fun `Android 10 and 11 need precise location only`() {
        assertEquals(listOf(PermissionPolicy.FINE_LOCATION), PermissionPolicy.runtimePermissions(29))
        assertEquals(listOf(PermissionPolicy.FINE_LOCATION), PermissionPolicy.runtimePermissions(30))
    }

    @Test
    fun `Android 12 still needs precise location, with the three Bluetooth permissions`() {
        // Question ouverte de mesh-08 : la documentation Nearby l'exige encore sur l'API 31
        assertEquals(bluetooth + PermissionPolicy.FINE_LOCATION + PermissionPolicy.COARSE_LOCATION, PermissionPolicy.runtimePermissions(31))
    }

    @Test
    fun `Android 12L drops precise location`() {
        assertEquals(bluetooth, PermissionPolicy.runtimePermissions(32))
    }

    @Test
    fun `Android 13 and later add nearby wifi devices`() {
        listOf(33, 34, 35, 36).forEach {
            assertEquals("api=$it", bluetooth + PermissionPolicy.NEARBY_WIFI_DEVICES, PermissionPolicy.runtimePermissions(it))
        }
    }

    @Test
    fun `notifications are requested from Android 13 only, and are not required to run`() {
        assertTrue(PermissionPolicy.notificationPermissions(32).isEmpty())
        assertEquals(listOf(PermissionPolicy.POST_NOTIFICATIONS), PermissionPolicy.notificationPermissions(33))
        assertFalse(PermissionPolicy.POST_NOTIFICATIONS in PermissionPolicy.missing(34, emptyList()))
        assertTrue(PermissionPolicy.POST_NOTIFICATIONS in PermissionPolicy.allRequested(34))
        assertFalse(PermissionPolicy.POST_NOTIFICATIONS in PermissionPolicy.allRequested(32))
    }

    @Test
    fun `missing lists only what is not granted`() {
        val granted = listOf(PermissionPolicy.BLUETOOTH_SCAN, PermissionPolicy.BLUETOOTH_CONNECT)
        assertEquals(
            listOf(PermissionPolicy.BLUETOOTH_ADVERTISE, PermissionPolicy.NEARBY_WIFI_DEVICES),
            PermissionPolicy.missing(34, granted)
        )
        assertTrue(PermissionPolicy.missing(34, PermissionPolicy.runtimePermissions(34)).isEmpty())
    }

    @Test
    fun `below minSdk is refused`() {
        assertThrows(IllegalArgumentException::class.java) { PermissionPolicy.runtimePermissions(28) }
    }

    // ── Cohérence avec le manifeste de la bibliothèque ───────────────────────

    private fun manifest(): String {
        // Le répertoire de travail d'un test unitaire est celui du module
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("manifeste introuvable : ${file.absolutePath}", file.exists())
        return file.readText()
    }

    @Test
    fun `every runtime permission the policy asks for is declared in the manifest`() {
        val manifest = manifest()
        (29..36).flatMap { PermissionPolicy.runtimePermissions(it) }.toSet().forEach { permission ->
            assertTrue("$permission non déclarée", manifest.contains("android:name=\"$permission\""))
        }
    }

    @Test
    fun `precise location is declared up to Android 12 only`() {
        val line = manifest().lines().single { it.contains(PermissionPolicy.FINE_LOCATION) }
        assertTrue(line, line.contains("android:maxSdkVersion=\"31\""))
    }

    @Test
    fun `approximate location is requested only with the precise one on Android 12`() {
        assertTrue(PermissionPolicy.COARSE_LOCATION in PermissionPolicy.runtimePermissions(31))
        assertTrue(PermissionPolicy.COARSE_LOCATION !in PermissionPolicy.runtimePermissions(30))
        assertTrue(PermissionPolicy.COARSE_LOCATION !in PermissionPolicy.runtimePermissions(32))
        val line = manifest().lines().single { it.contains(PermissionPolicy.COARSE_LOCATION) }
        assertTrue(line, line.contains("android:maxSdkVersion=\"31\""))
    }

    @Test
    fun `granting only approximate location on Android 12 leaves the mesh blocked`() {
        val granted = bluetooth + PermissionPolicy.COARSE_LOCATION
        assertEquals(listOf(PermissionPolicy.FINE_LOCATION), PermissionPolicy.missing(31, granted))
    }
}
