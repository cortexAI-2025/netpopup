package com.netpopup.transport.nearby.gms

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.netpopup.transport.nearby.NearbyApiException
import com.netpopup.transport.nearby.NearbyApi
import com.netpopup.transport.nearby.NearbyConfig
import com.netpopup.transport.nearby.NearbyListener
import com.netpopup.transport.nearby.NearbyPermissionException
import kotlinx.coroutines.tasks.await

/**
 * Nearby Connections réel. Enveloppe volontairement mince : toute la logique est dans
 * [com.netpopup.transport.nearby.NearbyTransport], testée sur la JVM. Ce fichier n'est
 * vérifié que par la compilation et par les tests sur appareils (mesh-08, #9).
 */
class GmsNearbyApi(
    context: Context,
    private val serviceId: String = NearbyConfig.SERVICE_ID
) : NearbyApi {

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)

    @Volatile
    private var listener: NearbyListener? = null

    override fun setListener(listener: NearbyListener?) {
        this.listener = listener
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            listener?.onConnectionInitiated(endpointId, info.endpointName, info.isIncomingConnection)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) listener?.onConnectionFailure(endpointId, result.status.statusCode)
            listener?.onConnectionResult(endpointId, result.status.isSuccess)
        }

        override fun onDisconnected(endpointId: String) {
            listener?.onDisconnected(endpointId)
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            listener?.onEndpointFound(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) {
            listener?.onEndpointLost(endpointId)
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            // Seules les charges utiles BYTES sont utilisées : fichiers et flux sont ignorés
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { listener?.onPayload(endpointId, it) }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    override suspend fun startAdvertising(localName: String) = call(ALREADY_ADVERTISING) {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(localName, serviceId, lifecycle, options).await()
    }

    override suspend fun stopAdvertising() {
        client.stopAdvertising()
    }

    override suspend fun startDiscovery() = call(ALREADY_DISCOVERING) {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startDiscovery(serviceId, discovery, options).await()
    }

    override suspend fun stopDiscovery() {
        client.stopDiscovery()
    }

    override suspend fun requestConnection(localName: String, endpointId: String) =
        call(ALREADY_CONNECTED) { client.requestConnection(localName, endpointId, lifecycle).await() }

    override suspend fun acceptConnection(endpointId: String) =
        call { client.acceptConnection(endpointId, payloads).await() }

    override suspend fun rejectConnection(endpointId: String) =
        call { client.rejectConnection(endpointId).await() }

    override suspend fun sendBytes(endpointId: String, bytes: ByteArray) =
        call { client.sendPayload(endpointId, Payload.fromBytes(bytes)).await() }

    override suspend fun disconnect(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
    }

    override suspend fun stopAll() {
        client.stopAllEndpoints()
    }

    /**
     * Exécute un appel Nearby. Un `SecurityException` devient [NearbyPermissionException]
     * (inutile de réessayer) ; les codes « déjà en cours » de [okStatuses] sont un succès.
     */
    private suspend inline fun call(vararg okStatuses: Int, block: () -> Unit) {
        try {
            block()
        } catch (e: ApiException) {
            if (e.statusCode !in okStatuses) throw NearbyApiException(e.statusCode, e)
        } catch (e: SecurityException) {
            throw NearbyPermissionException("Nearby permission missing", e)
        }
    }

    private companion object {
        // Codes de ConnectionsStatusCodes, en clair pour ne dépendre d'aucun nom de constante
        const val ALREADY_ADVERTISING = 8001
        const val ALREADY_DISCOVERING = 8002
        const val ALREADY_CONNECTED = 8003
    }
}
