package com.netpopup.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoUtilsTest {

    @Test
    fun `rounds to two decimals and encodes dots`() {
        assertEquals("zone_48p86_2p35", GeoUtils.computeZoneId(48.8566, 2.3522))
    }

    @Test
    fun `encodes negative coordinates`() {
        assertEquals("zone_n33p87_n151p21", GeoUtils.computeZoneId(-33.8688, -151.2093))
    }

    @Test
    fun `nearby points share a zone`() {
        assertEquals(
            GeoUtils.computeZoneId(48.8561, 2.3521),
            GeoUtils.computeZoneId(48.8564, 2.3524)
        )
    }

    @Test
    fun `distant points get different zones`() {
        assertNotEquals(
            GeoUtils.computeZoneId(48.8566, 2.3522),
            GeoUtils.computeZoneId(48.8766, 2.3522)
        )
    }

    @Test
    fun `zone id is a valid Firestore id and FCM topic suffix`() {
        val id = GeoUtils.computeZoneId(-0.123, 179.999)
        assertTrue(id, id.matches(Regex("[a-zA-Z0-9_]+")))
    }

    @Test
    fun `display shows rounded coordinates`() {
        assertEquals("48.86, 2.35", GeoUtils.zoneDisplay(48.8566, 2.3522))
    }
}
