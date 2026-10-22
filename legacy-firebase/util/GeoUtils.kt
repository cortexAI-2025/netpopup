package com.netpopup.util

import kotlin.math.roundToInt

/**
 * Converts a GPS coordinate pair into a zone identifier.
 *
 * Algorithm:
 *   1. Round lat/lng to 2 decimal places → each cell is ~1.1 km × ~1.1 km.
 *   2. Encode the pair as a deterministic string (no hashing needed — the
 *      string itself IS the key and remains human-readable for debugging).
 *
 * Example:  (48.8566, 2.3522)  →  zone_48p86_2p35
 *
 * Note: no precise GPS coordinates are persisted anywhere.
 */
object GeoUtils {

    /**
     * Returns a zone ID from a lat/lng pair.
     * Replaces '.' with 'p' and '-' with 'n' to make it Firestore-key-safe.
     */
    fun computeZoneId(lat: Double, lng: Double): String {
        val roundedLat = (lat * 100).roundToInt() / 100.0
        val roundedLng = (lng * 100).roundToInt() / 100.0
        val latPart = roundedLat.toString().replace(".", "p").replace("-", "n")
        val lngPart = roundedLng.toString().replace(".", "p").replace("-", "n")
        return "zone_${latPart}_${lngPart}"
    }

    /**
     * Returns a human-readable approximation of the zone (for UI display).
     * e.g. "48.86, 2.35"
     */
    fun zoneDisplay(lat: Double, lng: Double): String {
        val roundedLat = (lat * 100).roundToInt() / 100.0
        val roundedLng = (lng * 100).roundToInt() / 100.0
        return "$roundedLat, $roundedLng"
    }
}
