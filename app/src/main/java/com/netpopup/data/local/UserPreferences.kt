package com.netpopup.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

/**
 * Persists lightweight user state locally using Jetpack DataStore.
 *
 * Stored keys:
 *  - user_id       : Firebase UID (set after anonymous sign-in)
 *  - username      : auto-generated display name
 *  - blocked_users : set of user IDs the local user has blocked
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
        val FCM_TOKEN     = stringPreferencesKey("fcm_token")
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
    val blockedUsersFlow: Flow<Set<String>> =
        dataStore.data.map { it[Keys.BLOCKED_USERS] ?: emptySet() }

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
        dataStore.edit { prefs ->
            val current = prefs[Keys.BLOCKED_USERS] ?: emptySet()
            prefs[Keys.BLOCKED_USERS] = current + blockedUserId
        }
    }

    /** Removes [userId] from the local block list (unblock). */
    suspend fun unblockUser(userId: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.BLOCKED_USERS] ?: emptySet()
            prefs[Keys.BLOCKED_USERS] = current - userId
        }
    }

    suspend fun isUserBlocked(userId: String): Boolean {
        val prefs = dataStore.data.first()
        return userId in (prefs[Keys.BLOCKED_USERS] ?: emptySet())
    }
}
