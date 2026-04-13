package com.netpopup.util

/**
 * Generates random, memorable anonymous usernames such as "Wolf_4821".
 * Names are lightweight — no personal info is encoded.
 */
object UsernameGenerator {

    private val animals = listOf(
        "Wolf", "Fox", "Bear", "Eagle", "Hawk", "Lion", "Tiger",
        "Cobra", "Falcon", "Raven", "Lynx", "Panda", "Shark",
        "Blaze", "Frost", "Nova", "Ghost", "Storm", "Cipher",
        "Viper", "Pixel", "Neon", "Flux", "Echo", "Zen"
    )

    /** Returns a name like "Eagle_7312" */
    fun generate(): String {
        val word = animals.random()
        val number = (1000..9999).random()
        return "${word}_$number"
    }
}
