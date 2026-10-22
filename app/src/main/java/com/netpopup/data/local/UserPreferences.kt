package com.netpopup.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.netpopup.data.model.User
import com.netpopup.storage.MeshDatabase
import com.netpopup.util.UsernameGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Extension property — creates a single DataStore instance per process
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("netpopup_prefs")

/**
 * Persists lightweight user state locally using Jetpack DataStore.
 *
 * Stored keys (non-sensitive only — DataStore is not encrypted):
 *  - user_id       : Firebase UID (set after anonymous sign-in)
 *  - username      : auto-generated display name
 *  - fcm_token     : push token
 *
 * The block list lives in the encrypted mesh database ([MeshDatabase]); the legacy DataStore
 * set is imported once and then deleted.
 */
@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val meshDatabase: MeshDatabase
) {
    private val dataStore = context.dataStore

    private object Keys {
        val USER_ID       = stringPreferencesKey("user_id")
        val USERNAME      = stringPreferencesKey("username")
        /** Ancienne liste, importée dans la base chiffrée puis supprimée. */
        val BLOCKED_USERS = stringSetPreferencesKey("blocked_users")
        val FCM_TOKEN     = stringPreferencesKey("fcm_token")
    }

    // ── Block list (encrypted database) ───────────────────────────────────────

    private val blocked = MutableStateFlow<Set<String>>(emptySet())
    @Volatile private var blockListLoaded = false

    private val loadMutex = Mutex()

    /**
     * Charge la liste depuis la base chiffrée, après import de l'ancienne liste du DataStore.
     * L'import précède l'effacement : si l'app s'arrête entre les deux, il est simplement rejoué.
     */
    private suspend fun ensureBlockListLoaded() {
        if (blockListLoaded) return
        loadMutex.withLock {
            if (blockListLoaded) return
            val legacy = dataStore.data.first()[Keys.BLOCKED_USERS].orEmpty()
            withContext(Dispatchers.IO) {
                val list = meshDatabase.blockList
                if (legacy.isNotEmpty()) list.importLegacy(legacy, System.currentTimeMillis())
                blocked.value = list.all()
            }
            if (legacy.isNotEmpty()) dataStore.edit { it.remove(Keys.BLOCKED_USERS) }
            blockListLoaded = true
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Returns the persisted [User], or null if not yet initialized. */
    suspend fun getUser(): User? {
        val prefs = dataStore.data.first()
        val id = prefs[Keys.USER_ID] ?: return null
        val username = prefs[Keys.USERNAME] ?: UsernameGenerator.generate()
        return User(id = id, username = username)
    }

    /** Reactive stream of the current username (for real-time UI updates). */
    val usernameFlow: Flow<String?> = dataStore.data.map { it[Keys.USERNAME] }

    /** Reactive stream of locally blocked user IDs. */
    val blockedUsersFlow: Flow<Set<String>> = blocked.onStart { ensureBlockListLoaded() }

    // ── Write ─────────────────────────────────────────────────────────────────

    /** Persists the Firebase UID and auto-generated username after sign-in. */
    suspend fun saveUser(userId: String, username: String) {
        dataStore.edit { prefs ->
            prefs[Keys.USER_ID] = userId
            prefs[Keys.USERNAME] = username
        }
    }

    /** Saves the latest FCM device token for push notification targeting. */
    suspend fun saveFcmToken(token: String) {
        dataStore.edit { it[Keys.FCM_TOKEN] = token }
    }

    /** Adds [blockedUserId] to the local block list. */
    suspend fun blockUser(blockedUserId: String) {
        ensureBlockListLoaded()
        withContext(Dispatchers.IO) {
            meshDatabase.blockList.block(blockedUserId, System.currentTimeMillis())
            blocked.value = meshDatabase.blockList.all()
        }
    }

    /** Removes [userId] from the local block list (unblock). */
    suspend fun unblockUser(userId: String) {
        ensureBlockListLoaded()
        withContext(Dispatchers.IO) {
            meshDatabase.blockList.unblock(userId)
            blocked.value = meshDatabase.blockList.all()
        }
    }

    suspend fun isUserBlocked(userId: String): Boolean {
        ensureBlockListLoaded()
        return userId in blocked.value
    }
}
