package com.netpopup.mesh

import android.content.Context
import com.netpopup.dtn.chat.MeshStatus
import com.netpopup.dtn.transport.LinkEvent
import com.netpopup.dtn.transport.PeerHandle
import com.netpopup.dtn.transport.TransportManager
import com.netpopup.transport.nearby.NearbyTransport
import com.netpopup.transport.nearby.gms.GmsNearbyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Le mesh en marche : un [TransportManager] au-dessus du transport Nearby.
 *
 * Pour l'instant il ouvre et tourne les liens et expose [status] ; les trames reçues ne
 * sont pas encore branchées sur la synchronisation (session d'anti-entropie, issue 6),
 * ce qui viendra avec la migration de l'UI (mesh-15, #16).
 */
object MeshRuntime {

    private val _status = MutableStateFlow(MeshStatus.STOPPED)
    val status: StateFlow<MeshStatus> = _status.asStateFlow()

    private var scope: CoroutineScope? = null
    private var manager: TransportManager? = null

    @Synchronized
    fun start(context: Context) {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val nearby = NearbyTransport(GmsNearbyApi(context.applicationContext), s)
        val m = TransportManager(listOf(nearby), s)
        scope = s
        manager = m

        val peers = HashSet<PeerHandle>()
        // UNDISPATCHED : on est abonné avant que le gestionnaire ne démarre
        s.launch(start = CoroutineStart.UNDISPATCHED) {
            m.events.collect { event ->
                when (event) {
                    is LinkEvent.LinkUp -> peers += event.peer
                    is LinkEvent.LinkDown -> peers -= event.peer
                    is LinkEvent.FrameReceived -> Unit
                }
                _status.update { it.copy(peersInRange = peers.size) }
            }
        }
        s.launch {
            m.start()
            _status.update { it.copy(isRunning = true) }
        }
    }

    fun stop() {
        val (s, m) = synchronized(this) {
            val current = scope to manager
            scope = null
            manager = null
            current
        }
        if (s == null || m == null) return
        s.launch {
            m.stop()
            _status.value = MeshStatus.STOPPED
            s.cancel()
        }
    }
}
