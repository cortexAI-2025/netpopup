package com.netpopup.mesh

import android.content.Context
import com.netpopup.dtn.chat.ChatRepository
import com.netpopup.dtn.chat.MeshStatus
import com.netpopup.engine.EngineConfig
import com.netpopup.engine.MeshChatRepository
import com.netpopup.dtn.presentation.LifetimeChoices
import com.netpopup.engine.MeshEngine
import com.netpopup.settings.AppSettings
import com.netpopup.storage.MeshDatabase
import com.netpopup.transport.nearby.NearbyTransport
import com.netpopup.transport.nearby.gms.GmsNearbyApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Le mesh en marche : un [MeshEngine] (store chiffré, crypto, rooms, ACK, synchronisation) au-dessus du
 * transport Nearby, et le [ChatRepository] que l'interface utilise.
 *
 * Le moteur exige un fil unique pour tout son état, base comprise : [engineScope] en fournit un. Il est
 * construit à la première demande, hors du fil principal (Keystore et SQLCipher sont lents).
 *
 * Le dépôt existe dès l'ouverture de l'app, avant même le démarrage du service : ses opérations attendent
 * le moteur. Les messages composés hors portée sont donc gardés et partiront à la prochaine rencontre.
 */
@Singleton
class MeshRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MeshDatabase,
    private val settings: AppSettings,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val engine: Deferred<MeshEngine> = engineScope.async {
        val nearby = NearbyTransport(GmsNearbyApi(context.applicationContext), engineScope)
        MeshEngine(
            db = database.sql,
            transports = listOf(nearby),
            parentScope = engineScope,
            config = EngineConfig(
                displayName = { settings.snapshot.displayName },
                messageLifetimeSeconds = { LifetimeChoices.toSeconds(settings.snapshot.lifetimeHours) },
            ),
        )
    }

    /** Interface de l'UI : n'ouvre la base qu'au premier usage et ne bloque jamais le fil principal. */
    val repository: ChatRepository = DeferredChatRepository(engineScope.async { MeshChatRepository(engine.await()) })

    private val _status = MutableStateFlow(MeshStatus.STOPPED)
    val status: StateFlow<MeshStatus> = _status.asStateFlow()

    @Volatile private var started = false
    private var statusJob: kotlinx.coroutines.Job? = null

    fun start() {
        if (started) return
        started = true
        engineScope.launch {
            val e = engine.await()
            statusJob?.cancel()
            statusJob = launch { e.meshStatus.collect { _status.value = it } }
            e.start()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        engineScope.launch {
            statusJob?.cancel()
            engine.await().stop()
            _status.value = MeshStatus.STOPPED
        }
    }
}
