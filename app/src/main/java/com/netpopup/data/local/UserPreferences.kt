package com.netpopup.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.netpopup.data.model.User
import com.netpopup.util.UsernameGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Extension property — creates a single DataStore instance per process
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("netpopup_prefs")

/** A blocked user as shown in Settings. [username] may be blank for legacy entries. */
data class BlockedUser(val id: String, val username: String)

/**
 * Persists lightweight user state locally using Jetpack DataStore.
 *
 * Stored keys:
 *  - user_id          : Firebase UID (set after anonymous sign-in)
 *  - username         : auto-generated display name
 *  - blocked_users    : set of user IDs the local user has blocked
 *  - blocked_names    : "uid::username" entries (display only, for Settings)
 *  - recent_rooms     : private room codes the user created or joined
 *  - local_topic      : FCM topic of the current geo zone (to unsubscribe on zone change)
 *  - notifications    : push notifications enabled (default true)
 */
@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    private object Keys {
        val USER_ID       = stringPreferencesKey("user_id")
        val USERNAME      = stringPreferencesKey("username")
        val BLOCKED_USERS = stringSetPreferencesKey("blocked_users")
        val BLOCKED_NAMES = stringSetPreferencesKey("blocked_names")
        val RECENT_ROOMS  = stringSetPreferencesKey("recent_rooms")
        val LOCAL_TOPIC   = stringPreferencesKey("local_topic")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val FCM_TOKEN     = stringPreferencesKey("fcm_token")
    }

    private companion object {
        const val NAME_SEPARATOR = "::"
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Returns the persisted [User], or null if not yet initialized. */
    suspend fun getUser(): User? {
        val prefs = dataStore.data.first()
        val id = prefs[Keys.USER_ID] ?: return null
        val username = prefs[Keys.USERNAME] ?: UsernameGenerator.generate()
        return User(id = id, username = username)
    }

    /** The persisted Firebase UID, or null before the first sign-in. */
    suspend fun getUserId(): String? = dataStore.data.first()[Keys.USER_ID]

    /** Reactive stream of the current username (for real-time UI updates). */
    val usernameFlow: Flow<String?> = dataStore.data.map { it[Keys.USERNAME] }

    /** Reactive stream of locally blocked user IDs. */
    val blockedUsersFlow: Flow<Set<String>> =
        dataStore.data.map { it[Keys.BLOCKED_USERS] ?: emptySet() }

    /** Blocked users with their last known username, sorted by name. */
    val blockedUsersDetailedFlow: Flow<List<BlockedUser>> = dataStore.data.map { prefs ->
        val ids = prefs[Keys.BLOCKED_USERS] ?: emptySet()
        val names = (prefs[Keys.BLOCKED_NAMES] ?: emptySet())
            .mapNotNull { entry ->
                val parts = entry.split(NAME_SEPARATOR, limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
        ids.map { BlockedUser(id = it, username = names[it].orEmpty()) }
            .sortedBy { it.username.ifBlank { it.id } }
    }

    /** Private room codes the user has created or joined, sorted. */
    val recentRoomsFlow: Flow<List<String>> =
        dataStore.data.map { (it[Keys.RECENT_ROOMS] ?: emptySet()).sorted() }

    val notificationsEnabledFlow: Flow<Boolean> =
        dataStore.data.map { it[Keys.NOTIFICATIONS] ?: true }

    suspend fun isNotificationsEnabled(): Boolean = notificationsEnabledFlow.first()

    suspend fun getLocalTopic(): String? = dataStore.data.first()[Keys.LOCAL_TOPIC]

    // ── Write ─────────────────────────────────────────────────────────────────

    /** Persists the Firebase UID and auto-generated username after sign-in. */
    suspend fun saveUser(userId: String, username: String) {
        dataStore.edit { prefs ->
            prefs[Keys.USER_ID] = userId
            prefs[Keys.USERNAME] = username
        }
    }

    /** Replaces the display name. Already-sent messages keep the old one. */
    suspend fun saveUsername(username: String) {
        dataStore.edit { it[Keys.USERNAME] = username }
    }

    /** Saves the latest FCM device token for push notification targeting. */
    suspend fun saveFcmToken(token: String) {
        dataStore.edit { it[Keys.FCM_TOKEN] = token }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATIONS] = enabled }
    }

    suspend fun setLocalTopic(topic: String) {
        dataStore.edit { it[Keys.LOCAL_TOPIC] = topic }
    }

    suspend fun addRecentRoom(code: String) {
        dataStore.edit { prefs ->
            prefs[Keys.RECENT_ROOMS] = (prefs[Keys.RECENT_ROOMS] ?: emptySet()) + code
        }
    }

    suspend fun removeRecentRoom(code: String) {
        dataStore.edit { prefs ->
            prefs[Keys.RECENT_ROOMS] = (prefs[Keys.RECENT_ROOMS] ?: emptySet()) - code
        }
    }

    /** Adds [blockedUserId] to the local block list. */
    suspend fun blockUser(blockedUserId: String, username: String = "") {
        dataStore.edit { prefs ->
            val current = prefs[Keys.BLOCKED_USERS] ?: emptySet()
            prefs[Keys.BLOCKED_USERS] = current + blockedUserId
            if (username.isNotBlank()) {
                val names = (prefs[Keys.BLOCKED_NAMES] ?: emptySet())
                    .filterNot { it.startsWith(blockedUserId + NAME_SEPARATOR) }
                    .toSet()
                prefs[Keys.BLOCKED_NAMES] = names + "$blockedUserId$NAME_SEPARATOR$username"
            }
        }
    }

    /** Removes [userId] from the local block list (unblock). */
    suspend fun unblockUser(userId: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.BLOCKED_USERS] ?: emptySet()
            prefs[Keys.BLOCKED_USERS] = current - userId
            prefs[Keys.BLOCKED_NAMES] = (prefs[Keys.BLOCKED_NAMES] ?: emptySet())
                .filterNot { it.startsWith(userId + NAME_SEPARATOR) }
                .toSet()
        }
    }

    suspend fun isUserBlocked(userId: String): Boolean {
        val prefs = dataStore.data.first()
        return userId in (prefs[Keys.BLOCKED_USERS] ?: emptySet())
    }
}
