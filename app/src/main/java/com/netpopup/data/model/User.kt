package com.netpopup.data.model

/**
 * Represents an anonymous user.
 * The user ID comes from Firebase Anonymous Auth.
 * Username is auto-generated and stored locally in DataStore.
 */
data class User(
    val id: String = "",
    val username: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
