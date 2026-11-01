package com.netpopup.transport.nearby

/**
 * Ce que le transport Nearby observe, pour l'écran de diagnostic : sans lui, une erreur de Nearby (Bluetooth coupé,
 * permission refusée, position désactivée) ne laisse aucune trace visible. Aucune donnée de conversation.
 */
data class NearbyDiagnostics(
    /** Cycles de diffusion lancés depuis le démarrage. */
    val cycles: Int = 0,
    /** `null` : la diffusion démarre sans erreur ; sinon la dernière erreur de `startAdvertising`. */
    val advertisingError: String? = null,
    /** `null` : la recherche démarre sans erreur ; sinon la dernière erreur de `startDiscovery`. */
    val discoveryError: String? = null,
    /** Téléphones (points d'accès Nearby) aperçus par la recherche, ce téléphone-ci excepté. */
    val endpointsFound: Int = 0,
    /** Connexions demandées par ce téléphone. */
    val connectionRequests: Int = 0,
    /** Connexions proposées par un autre téléphone. */
    val connectionsProposed: Int = 0,
    /** Connexions établies. */
    val connectionsOpened: Int = 0,
    /** Dernier échec de connexion (demande refusée, poignée de main échouée). */
    val lastConnectionError: String? = null,
)
