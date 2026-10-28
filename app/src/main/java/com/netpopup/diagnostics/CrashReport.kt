package com.netpopup.diagnostics

import java.io.File

/**
 * Garde en local la trace du dernier plantage, pour que l'utilisateur puisse la copier au prochain lancement
 * (aucun envoi, aucun réseau). Ne contient que la pile d'appels : classes et lignes de code, pas de contenu de
 * conversation ; le message de l'exception est tronqué.
 */
class CrashReport(private val file: File) {

    fun record(thread: Thread, error: Throwable) {
        try {
            val trace = generateSequence(error) { it.cause }.take(6).joinToString("\n\nCaused by:\n") { t ->
                t.javaClass.name + ": " + (t.message ?: "").take(MAX_MESSAGE) + "\n" +
                    t.stackTrace.take(MAX_FRAMES).joinToString("\n") { "    at $it" }
            }
            file.parentFile?.mkdirs()
            file.writeText("thread=${thread.name}\n$trace\n")
        } catch (_: Exception) {
            // Pendant un plantage, mieux vaut ne rien écrire que d'en provoquer un autre.
        }
    }

    /** La trace du dernier plantage, ou null s'il n'y en a pas. */
    fun pending(): String? = try {
        file.takeIf { it.isFile }?.bufferedReader()?.use { reader ->
            val buffer = CharArray(32_768)
            val count = reader.read(buffer)
            if (count > 0) String(buffer, 0, count).takeIf { it.isNotBlank() } else null
        }
    } catch (_: java.io.IOException) {
        null // A diagnostic must never prevent the application from starting.
    }

    fun clear() {
        file.delete()
    }

    /** Pose le gestionnaire ; le comportement normal d'Android (fermeture de l'app) est conservé. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(thread, error)
            previous?.uncaughtException(thread, error)
        }
    }

    private companion object {
        const val MAX_MESSAGE = 200
        const val MAX_FRAMES = 40
    }
}
