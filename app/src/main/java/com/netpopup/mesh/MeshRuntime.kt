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
import com.netpopup.transport.nearby.NearbyDiagnostics
import com.netpopup.transport.nearby.NearbyConfig
import com.netpopup.transport.nearby.NearbyTransport
import com.netpopup.transport.nearby.gms.GmsNearbyApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import com.netpopup.dtn.runtime.RetryableResource
import com.netpopup.diagnostics.CrashReport
import java.io.File
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
    private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val engineScope = CoroutineScope(SupervisorJob() + engineDispatcher)

    private val engine = RetryableResource {
        withContext(engineDispatcher) {
            val sql = database.sql
            val nearby = NearbyTransport(GmsNearbyApi(context.applicationContext), engineScope, NearbyConfig(continuous = true))
            MeshEngine(
                db = sql,
                transports = listOf(nearby),
                parentScope = engineScope,
                config = EngineConfig(
                    displayName = { settings.snapshot.displayName },
                    messageLifetimeSeconds = { LifetimeChoices.toSeconds(settings.snapshot.lifetimeHours) },
                ),
            ).also {
                engineScope.launch { nearby.diagnostics.collect { _nearbyDiagnostics.value = it } }
            }
        }
    }

    private val _nearbyDiagnostics = MutableStateFlow(NearbyDiagnostics())
    val nearbyDiagnostics: StateFlow<NearbyDiagnostics> = _nearbyDiagnostics.asStateFlow()

    /** Opens native storage and crypto before navigation creates any flow collectors. */
    suspend fun prepare() { engine.get() }

    /** Interface de l'UI : n'ouvre la base qu'au premier usage et ne bloque jamais le fil principal. */
    val repository: ChatRepository = DeferredChatRepository { MeshChatRepository(engine.get()) }

    private val _status = MutableStateFlow(MeshStatus.STOPPED)
    val status: StateFlow<MeshStatus> = _status.asStateFlow()

    @Volatile private var started = false
    private var statusJob: kotlinx.coroutines.Job? = null

    fun start() {
        if (started) return
        started = true
        engineScope.launch {
            try {
                val e = engine.get()
                if (!started) return@launch
                statusJob?.cancel()
                statusJob = launch { e.meshStatus.collect { _status.value = it } }
                e.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordStartupFailure(e)
            } catch (e: LinkageError) {
                recordStartupFailure(e)
            }
        }
    }

    private fun recordStartupFailure(error: Throwable) {
        started = false
        statusJob?.cancel()
        _status.value = MeshStatus.STOPPED
        CrashReport(File(context.filesDir, "last_crash.txt")).record(Thread.currentThread(), error)
    }

    fun stop() {
        if (!started) return
        started = false
        engineScope.launch {
            statusJob?.cancel()
            engine.peek()?.stop()
            _status.value = MeshStatus.STOPPED
        }
    }
}
