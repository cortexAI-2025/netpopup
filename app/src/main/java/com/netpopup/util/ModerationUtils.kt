package com.netpopup.util

/**
 * Client-side moderation utilities.
 *
 * The banned-word list is intentionally small for the MVP.
 * In production, replace this with a server-side check or a maintained list.
 */
object ModerationUtils {

    // Basic banned word set — extend as needed
    private val bannedWords: Set<String> = setOf(
        "spam", "scam", "phishing", "malware",
        "buy now", "click here", "free money", "bitcoin investment"
    )

    /**
     * Returns true if [text] contains any banned phrase (case-insensitive).
     */
    fun containsBannedWords(text: String): Boolean {
        val lower = text.lowercase()
        return bannedWords.any { lower.contains(it) }
    }

    /**
     * Trims whitespace and truncates to [Message.MAX_LENGTH] characters.
     */
    fun sanitize(text: String): String = text.trim().take(500)

    /**
     * Generates a random alphanumeric room code of [length] characters.
     */
    fun generateRoomCode(length: Int = 6): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no O/0, I/1 confusion
        return (1..length).map { chars.random() }.joinToString("")
    }
}
