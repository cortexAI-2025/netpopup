package com.netpopup.transport.internet

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Base64

/**
 * Relais Firestore joint par son API REST (aucun SDK Firebase : rien n'ajoute de pisteur ni de permission à l'APK
 * tant que ce module n'est pas relié à l'application).
 *
 * Rangement : `relay/{étiquette}/e/{id}` avec deux champs seulement, `b` (l'enveloppe chiffrée) et `x` (sa fin de
 * vie, politique TTL de Firestore sur `x`). On ne peut relever qu'en connaissant l'étiquette (les règles interdisent
 * de lister `relay` et les requêtes de groupe de collection : voir `relay/firestore.rules`).
 *
 * L'authentification est **anonyme**, uniquement pour le contrôle d'abus : aucun compte, aucun identifiant NetPopUp
 * n'est envoyé. Le jeton est gardé en mémoire (un nouveau compte anonyme par lancement du processus).
 */
class FirestoreRestBackend(
    private val projectId: String,
    private val apiKey: String,
    private val firestoreBase: String = "https://firestore.googleapis.com",
    private val authBase: String = "https://identitytoolkit.googleapis.com",
    private val tokenBase: String = "https://securetoken.googleapis.com",
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Int = 10_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RelayBackend {

    private class Reply(val code: Int, val body: String)

    private val authLock = Mutex()
    private var idToken: String? = null
    private var refreshToken: String? = null
    private var tokenExpiresAt = 0L

    override suspend fun publish(doc: RelayDocument): PublishResult {
        val body = JSONObject().put(
            "fields", JSONObject()
                .put("b", JSONObject().put("bytesValue", Base64.getEncoder().encodeToString(doc.envelope)))
                .put("x", JSONObject().put("timestampValue", Instant.ofEpochSecond(doc.expiresAtSeconds).toString()))
        ).toString()
        val reply = authorized { token -> post("$documents/relay/${doc.tagHex}/e?documentId=${doc.idHex}", body, token) }
            ?: return PublishResult.FAILED
        return when (reply.code) {
            200 -> PublishResult.CREATED
            409 -> PublishResult.EXISTS
            400, 403, 413 -> PublishResult.REJECTED
            else -> PublishResult.FAILED
        }
    }

    override suspend fun fetch(destTag: ByteArray, nowSeconds: Long, limit: Int): FetchResult {
        val reply = authorized { token -> get("$documents/relay/${destTag.toHex()}/e?pageSize=$limit", token) }
            ?: return FetchResult(null)
        if (reply.code == 404) return FetchResult(emptyList())
        if (reply.code != 200) return FetchResult(null)
        return try {
            FetchResult(parse(reply.body, destTag, nowSeconds, limit))
        } catch (e: JSONException) {
            FetchResult(null)
        }
    }

    // ── Lecture de la réponse ────────────────────────────────────────────────

    /** Ignore tout document mal formé : le relais est hostile par défaut, comme tout pair. */
    private fun parse(json: String, destTag: ByteArray, nowSeconds: Long, limit: Int): List<RelayDocument> {
        val docs: JSONArray = JSONObject(json).optJSONArray("documents") ?: return emptyList()
        val out = ArrayList<RelayDocument>()
        for (i in 0 until minOf(docs.length(), limit)) {
            val d = docs.optJSONObject(i) ?: continue
            val id = d.optString("name").substringAfterLast('/').hexToBytes() ?: continue
            val fields = d.optJSONObject("fields") ?: continue
            val bytes = try {
                Base64.getDecoder().decode(fields.optJSONObject("b")?.optString("bytesValue").orEmpty())
            } catch (e: IllegalArgumentException) { continue }
            val expires = try {
                Instant.parse(fields.optJSONObject("x")?.optString("timestampValue").orEmpty()).epochSecond
            } catch (e: java.time.format.DateTimeParseException) { continue }
            if (expires <= nowSeconds) continue
            val doc = try { RelayDocument(id, destTag, expires, bytes) } catch (e: IllegalArgumentException) { continue }
            out += doc
        }
        return out
    }

    // ── Authentification anonyme ─────────────────────────────────────────────

    /** Exécute [call] avec un jeton valide ; sur 401, renouvelle le jeton une fois. `null` : injoignable ou non authentifié. */
    private suspend fun authorized(call: (String) -> Reply): Reply? = withContext(io) {
        try {
            var token = token(forceRefresh = false) ?: return@withContext null
            var reply = call(token)
            if (reply.code == 401) {
                token = token(forceRefresh = true) ?: return@withContext null
                reply = call(token)
            }
            if (reply.code == 401) null else reply
        } catch (e: IOException) {
            null
        }
    }

    private suspend fun token(forceRefresh: Boolean): String? = authLock.withLock {
        val current = idToken
        if (!forceRefresh && current != null && clock() < tokenExpiresAt - TOKEN_MARGIN_MILLIS) return@withLock current
        val refreshed = refreshToken?.let { refresh(it) } ?: false
        if (!refreshed) signUp()
        idToken
    }

    private fun signUp() {
        val reply = post("$authBase/v1/accounts:signUp?key=$apiKey", """{"returnSecureToken":true}""", null)
        if (reply.code != 200) { idToken = null; return }
        val j = parseObject(reply.body) ?: run { idToken = null; return }
        store(j.optString("idToken"), j.optString("refreshToken"), j.optString("expiresIn"))
    }

    private fun refresh(token: String): Boolean {
        val reply = form("$tokenBase/v1/token?key=$apiKey", "grant_type=refresh_token&refresh_token=$token")
        if (reply.code != 200) return false
        val j = parseObject(reply.body) ?: return false
        store(j.optString("id_token"), j.optString("refresh_token"), j.optString("expires_in"))
        return idToken != null
    }

    private fun store(id: String, refresh: String, expiresInSeconds: String) {
        idToken = id.ifEmpty { null }
        refreshToken = refresh.ifEmpty { null }
        tokenExpiresAt = clock() + (expiresInSeconds.toLongOrNull() ?: DEFAULT_TOKEN_SECONDS) * 1000
    }

    private fun parseObject(s: String): JSONObject? = try { JSONObject(s) } catch (e: JSONException) { null }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private val documents get() = "$firestoreBase/v1/projects/$projectId/databases/(default)/documents"

    private fun post(url: String, json: String, token: String?): Reply = send(url, "POST", json.toByteArray(), "application/json", token)

    private fun form(url: String, body: String): Reply = send(url, "POST", body.toByteArray(), "application/x-www-form-urlencoded", null)

    private fun get(url: String, token: String?): Reply = send(url, "GET", null, null, token)

    /** Une requête, sans cookie, sans cache, sans en-tête d'identification : seulement le jeton anonyme. */
    private fun send(url: String, method: String, body: ByteArray?, contentType: String?, token: String?): Reply {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMillis
            c.readTimeout = timeoutMillis
            c.useCaches = false
            c.instanceFollowRedirects = false
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (contentType != null) c.setRequestProperty("Content-Type", contentType)
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { it.readBytes().take(MAX_RESPONSE_BYTES).toByteArray().toString(Charsets.UTF_8) }.orEmpty()
            return Reply(code, text)
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val TOKEN_MARGIN_MILLIS = 60_000L
        const val DEFAULT_TOKEN_SECONDS = 3_600L
        /** 50 documents de 4 Ko en base64 : on ne lit pas plus qu'une réponse raisonnable. */
        const val MAX_RESPONSE_BYTES = 1_000_000
    }
}
