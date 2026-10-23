package com.netpopup.transport.nearby

/** Nearby joué à la main : enregistre les appels du transport et déclenche les rappels. */
class FakeNearbyApi : NearbyApi {

    var listener: NearbyListener? = null
        private set

    /** Journal des appels, dans l'ordre : `advertise:<nom>`, `request:<nom>:<id>`, `accept:<id>`... */
    val calls = mutableListOf<String>()
    val sent = mutableListOf<Pair<String, ByteArray>>()

    var advertiseFailure: Exception? = null
    var discoveryFailure: Exception? = null
    var requestFailure: Exception? = null
    var acceptFailure: Exception? = null
    var sendFailure: Exception? = null

    override fun setListener(listener: NearbyListener?) { this.listener = listener }

    override suspend fun startAdvertising(localName: String) {
        calls += "advertise:$localName"
        advertiseFailure?.let { throw it }
    }

    override suspend fun stopAdvertising() { calls += "stopAdvertising" }

    override suspend fun startDiscovery() {
        calls += "discover"
        discoveryFailure?.let { throw it }
    }

    override suspend fun stopDiscovery() { calls += "stopDiscovery" }

    override suspend fun requestConnection(localName: String, endpointId: String) {
        calls += "request:$localName:$endpointId"
        requestFailure?.let { throw it }
    }

    override suspend fun acceptConnection(endpointId: String) {
        calls += "accept:$endpointId"
        acceptFailure?.let { throw it }
    }

    override suspend fun rejectConnection(endpointId: String) { calls += "reject:$endpointId" }

    override suspend fun sendBytes(endpointId: String, bytes: ByteArray) {
        sendFailure?.let { throw it }
        calls += "send:$endpointId:${bytes.size}"
        sent += endpointId to bytes
    }

    override suspend fun disconnect(endpointId: String) { calls += "disconnect:$endpointId" }

    override suspend fun stopAll() { calls += "stopAll" }

    // ── Helpers de test ──────────────────────────────────────────────────────

    /** Noms diffusés, dans l'ordre des cycles. */
    fun advertisedNames(): List<String> = calls.filter { it.startsWith("advertise:") }.map { it.removePrefix("advertise:") }

    fun count(prefix: String) = calls.count { it.startsWith(prefix) }

    /** Une connexion complète vue de Nearby : proposée, puis établie. */
    fun connect(endpointId: String, incoming: Boolean = true, remoteName: String = "peer") {
        listener!!.onConnectionInitiated(endpointId, remoteName, incoming)
        listener!!.onConnectionResult(endpointId, true)
    }
}
