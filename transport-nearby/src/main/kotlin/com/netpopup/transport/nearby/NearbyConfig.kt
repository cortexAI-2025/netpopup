package com.netpopup.transport.nearby

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Réglages du transport Nearby. Les durées de cycle sont un point de départ, à calibrer
 * sur appareils réels au test terrain (mesh-23, #24).
 */
data class NearbyConfig(
    /** Identifiant de service Nearby : deux apps ne se voient que s'il est identique. */
    val serviceId: String = SERVICE_ID,

    /** Diffusion et recherche actives pendant cette durée... */
    val activeDuration: Duration = 20.seconds,

    /** ... puis coupées pendant celle-ci pour économiser la batterie (les connexions restent). */
    val idleDuration: Duration = 40.seconds,

    /** Connexions simultanées au plus (architecture mesh v0.2 : 3 à 4). */
    val maxConnections: Int = 4,

    /** Continuous discovery avoids permanently disjoint 20s/40s windows on separate phones. */
    val continuous: Boolean = false,

    /** A missing callback must not occupy a connection slot forever. */
    val connectionTimeout: Duration = 15.seconds,
) {
    init {
        require(serviceId.isNotBlank()) { "serviceId must not be blank" }
        require(activeDuration > Duration.ZERO) { "activeDuration must be > 0" }
        require(!idleDuration.isNegative()) { "idleDuration must be >= 0" }
        require(connectionTimeout > Duration.ZERO) { "connectionTimeout must be > 0" }
        require(maxConnections in 1..8) { "maxConnections must be in 1..8" }
    }

    companion object {
        const val SERVICE_ID = "com.netpopup.mesh.v0"

        /** Plus grande charge utile `BYTES` de Nearby Connections. */
        const val MAX_FRAME_BYTES = 32_768
    }
}
