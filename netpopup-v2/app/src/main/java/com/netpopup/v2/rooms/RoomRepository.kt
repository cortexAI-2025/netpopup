package com.netpopup.v2.rooms

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Branchement Android de [RoomCore] : les salons (nom + clé maîtresse, jamais le mot de passe) sont gardés dans des
 * préférences chiffrées par le Keystore. Si le Keystore de l'appareil refuse, les salons restent en mémoire (perdus à la
 * fermeture) plutôt que d'être écrits en clair.
 */
object RoomRepository {
    private const val TAG = "NetPopUpRooms"
    private const val PREFS = "netpopup_rooms"

    @Volatile private var instance: RoomCore? = null

    val core: RoomCore
        get() = instance ?: error("RoomRepository.init(context) n'a pas été appelé")

    @Synchronized
    fun init(context: Context) {
        if (instance == null) instance = RoomCore(EncryptedStore(context.applicationContext))
    }

    /** Appelé par le mesh pour chaque diffusion reçue : `true` si c'était une enveloppe de salon (déjà traitée, à ne pas afficher). */
    fun consume(peerId: String, payload: ByteArray): Boolean =
        instance?.onPacket(peerId, payload) ?: RoomEnvelope.looksLikeRoomPacket(payload)

    /** Créer et rejoindre sont la même opération : qui connaît le nom et le mot de passe a la même clé. */
    suspend fun joinOrCreate(name: String, password: String): Room? = withContext(Dispatchers.Default) {
        try {
            core.add(name, RoomCrypto.deriveMasterKey(name, password))
        } catch (e: Exception) {
            Log.w(TAG, "key derivation failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private class EncryptedStore(context: Context) : RoomStore {
        private val prefs = try {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context, PREFS, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "encrypted storage unavailable: ${e.javaClass.simpleName}")
            null
        }

        override fun load(): List<StoredRoom> {
            val p = prefs ?: return emptyList()
            val count = p.getInt("count", 0)
            return (0 until count).mapNotNull { i ->
                val name = p.getString("n$i", null) ?: return@mapNotNull null
                val key = p.getString("k$i", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return@mapNotNull null
                if (key.size != RoomCrypto.MASTER_BYTES) null else StoredRoom(name, key)
            }
        }

        override fun save(rooms: List<StoredRoom>) {
            val p = prefs ?: return
            p.edit().clear().putInt("count", rooms.size).also { e ->
                rooms.forEachIndexed { i, r ->
                    e.putString("n$i", r.displayName)
                    e.putString("k$i", Base64.encodeToString(r.master, Base64.NO_WRAP))
                }
            }.apply()
        }
    }
}
