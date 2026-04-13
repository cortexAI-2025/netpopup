package com.netpopup.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.netpopup.data.local.UserPreferences
import com.netpopup.data.model.User
import com.netpopup.util.UsernameGenerator
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages anonymous Firebase Authentication.
 *
 * On first launch, [ensureSignedIn] creates a Firebase Anonymous account and
 * persists the UID + generated username to DataStore.  On subsequent launches
 * the same UID is reused (Firebase persists the credential internally).
 */
@Singleton
class AuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val userPreferences: UserPreferences
) {

    /**
     * Returns the current [User], signing in anonymously if needed.
     * This is safe to call multiple times — Firebase will not create a new
     * account if one already exists for this installation.
     */
    suspend fun ensureSignedIn(): User {
        // Try to recover from DataStore first (fast path)
        val cached = userPreferences.getUser()
        if (cached != null && auth.currentUser != null) return cached

        // Sign in anonymously via Firebase
        val result = auth.signInAnonymously().await()
        val uid = result.user?.uid ?: error("Anonymous sign-in returned null UID")

        val username = cached?.username ?: UsernameGenerator.generate()
        userPreferences.saveUser(userId = uid, username = username)

        return User(id = uid, username = username)
    }

    /** The currently authenticated Firebase UID, or null if not signed in. */
    val currentUserId: String? get() = auth.currentUser?.uid
}
