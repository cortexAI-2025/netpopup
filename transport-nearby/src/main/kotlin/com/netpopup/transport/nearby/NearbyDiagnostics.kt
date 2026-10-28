package com.netpopup.transport.nearby

/** Local radio diagnostics: counts and SDK status only, never payloads or identity keys. */
data class NearbyDiagnostics(
    val advertising: Boolean = false,
    val discovering: Boolean = false,
    val discovered: Int = 0,
    val connecting: Int = 0,
    val connected: Int = 0,
    val lastFailure: String? = null,
)
