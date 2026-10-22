package com.netpopup.transport.nearby

/**
 * Ce que le transport demande à Nearby Connections, réduit au strict nécessaire.
 *
 * Interface mince au-dessus de `ConnectionsClient` : toute la logique du transport
 * ([NearbyTransport]) est du Kotlin pur testable sur la JVM, et seule l'implémentation
 * `gms.GmsNearbyApi` touche Android. Les retours de Nearby passent par [NearbyListener],
 * appelé depuis n'importe quel thread.
 */
interface NearbyApi {
    /** Reçoit les rappels de Nearby ; `null` pour se détacher. */
    fun setListener(listener: NearbyListener?)

    suspend fun startAdvertising(localName: String)
    suspend fun stopAdvertising()
    suspend fun startDiscovery()
    suspend fun stopDiscovery()

    suspend fun requestConnection(localName: String, endpointId: String)
    suspend fun acceptConnection(endpointId: String)
    suspend fun rejectConnection(endpointId: String)

    /** Envoie [bytes] (32 768 octets au plus) en un seul `Payload` de type `BYTES`. */
    suspend fun sendBytes(endpointId: String, bytes: ByteArray)

    suspend fun disconnect(endpointId: String)

    /** Coupe toutes les connexions, la diffusion et la recherche. */
    suspend fun stopAll()
}

interface NearbyListener {
    fun onEndpointFound(endpointId: String, remoteName: String)
    fun onEndpointLost(endpointId: String)

    /** Une connexion est proposée : à accepter ou refuser des deux côtés pour qu'elle s'établisse. */
    fun onConnectionInitiated(endpointId: String, remoteName: String, isIncoming: Boolean)

    fun onConnectionResult(endpointId: String, success: Boolean)
    fun onDisconnected(endpointId: String)

    /** Charge utile de type `BYTES` seulement ; fichiers et flux sont ignorés par l'implémentation. */
    fun onPayload(endpointId: String, bytes: ByteArray)
}

/** Permission refusée ou manquante : inutile de réessayer tant que l'utilisateur ne l'a pas accordée. */
class NearbyPermissionException(message: String, cause: Throwable? = null) : Exception(message, cause)
