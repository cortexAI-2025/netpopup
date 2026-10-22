package com.netpopup.dtn.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Les tuyaux connus du mesh (architecture mesh v0.1, section 1). */
enum class TransportId { NEARBY, INTERNET, LORA }

/** Coût d'usage d'un transport : sert à préférer le gratuit au rare quand on a le choix. */
enum class CostClass { FREE, METERED, SCARCE }

data class TransportCapabilities(
    /** Plus grande trame envoyable en un seul morceau (Nearby `BYTES` : 32 768). */
    val maxFrameBytes: Int,
    /** Connexions simultanées que le transport sait tenir (Nearby : 3 à 4). */
    val maxConcurrentPeers: Int,
    /** Vrai pour LoRa : pas de session, on émet vers tous ceux à portée. */
    val isBroadcastOnly: Boolean,
    val costClass: CostClass
) {
    init {
        require(maxFrameBytes > 0) { "maxFrameBytes must be > 0" }
        require(maxConcurrentPeers > 0) { "maxConcurrentPeers must be > 0" }
    }
}

enum class TransportState {
    /** Radio absente, permission refusée, service coupé : rien ne peut passer. */
    UNAVAILABLE,

    /** Disponible mais arrêté. */
    IDLE,

    RUNNING,

    /** Tourne, mais mal (par exemple scan Bluetooth limité par le système). */
    DEGRADED
}

/**
 * Poignée d'un pair, **éphémère et propre à son transport**.
 *
 * Elle ne porte aucune identité NetPopUp : les pairs sont anonymes les uns pour les
 * autres (architecture mesh v0.1, section 1). Deux poignées de transports différents
 * ne sont jamais reliées entre elles, même si elles désignent le même téléphone.
 */
data class PeerHandle(val transport: TransportId, val value: String) {
    init {
        require(value.isNotEmpty()) { "peer handle value must not be empty" }
    }
}

sealed interface TransportEvent {
    data class PeerConnected(val peer: PeerHandle) : TransportEvent
    data class PeerLost(val peer: PeerHandle) : TransportEvent

    /** Les octets d'une trame reçue : toujours hostiles, c'est au cœur DTN de les valider. */
    class FrameReceived(val peer: PeerHandle, val frame: ByteArray) : TransportEvent
}

enum class SendResult {
    SENT,

    /** Le pair a disparu : le cœur DTN rattrape au prochain contact. */
    PEER_GONE,

    FRAME_TOO_LARGE,
    FAILED
}

/**
 * Un tuyau radio ou réseau : il ne fait que découvrir des pairs et échanger des trames
 * d'octets opaques. Il ne connaît ni l'enveloppe, ni la crypto, ni le routage, et ne
 * garde aucun état de message : un échec d'envoi n'est pas réessayé.
 */
interface Transport {
    val id: TransportId
    val capabilities: TransportCapabilities
    val state: StateFlow<TransportState>

    /** Pairs connectés ou perdus, trames reçues. Flux chaud : s'abonner avant [start]. */
    val events: Flow<TransportEvent>

    suspend fun start()
    suspend fun stop()

    /** Envoie une trame à un pair connecté, sans réessayer. */
    suspend fun send(peer: PeerHandle, frame: ByteArray): SendResult

    suspend fun disconnect(peer: PeerHandle)
}
