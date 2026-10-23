package com.netpopup.transport.internet

import com.netpopup.dtn.envelope.Envelope
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** Le client REST contre un faux Firestore local : ce qu'il envoie, ce qu'il accepte, comment il se remet des pannes. */
class FirestoreRestBackendTest {

    private class Request(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: String)

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Request>()
    private val store = LinkedHashMap<String, String>()          // chemin du document -> JSON stocké
    private var signUps = 0
    private var refreshes = 0
    private var tokenGeneration = 1
    @Volatile private var rejectTokensBelow = 0                  // les jetons plus anciens reçoivent 401
    @Volatile private var firestoreStatus: Int? = null           // force un code pour les appels Firestore
    @Volatile private var listBody: String? = null

    private val now = 1_800_000_000L
    private val tag = ByteArray(16) { 0x0a }
    private val id = ByteArray(16) { 0x0b }
    private fun envelopeBytes() = ByteArray(80) { 7 }

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
        requests += Request(ex.requestMethod, ex.requestURI.path, ex.requestURI.query, headers, body)
        val path = ex.requestURI.path
        fun reply(code: Int, text: String) { val b = text.toByteArray(); ex.sendResponseHeaders(code, if (b.isEmpty()) -1 else b.size.toLong()); if (b.isNotEmpty()) ex.responseBody.use { it.write(b) } else ex.close() }
        when {
            path == "/v1/accounts:signUp" -> { signUps++; reply(200, """{"idToken":"tok$tokenGeneration","refreshToken":"ref$tokenGeneration","expiresIn":"3600"}""") }
            path == "/v1/token" -> { refreshes++; tokenGeneration++; reply(200, """{"id_token":"tok$tokenGeneration","refresh_token":"ref$tokenGeneration","expires_in":"3600"}""") }
            else -> {
                val auth = headers["authorization"].orEmpty()
                val generation = auth.removePrefix("Bearer tok").toIntOrNull() ?: -1
                if (generation < rejectTokensBelow || generation < 0) return reply(401, """{"error":{"status":"UNAUTHENTICATED"}}""")
                firestoreStatus?.let { return reply(it, """{"error":{"status":"X"}}""") }
                if (ex.requestMethod == "POST") {
                    val docPath = path + "/" + ex.requestURI.query.orEmpty().substringAfter("documentId=")
                    if (docPath in store) reply(409, """{"error":{"status":"ALREADY_EXISTS"}}""") else { store[docPath] = body; reply(200, "{}") }
                } else {
                    listBody?.let { return reply(200, it) }
                    val docs = store.filterKeys { it.startsWith(path + "/") }.map { (p, b) ->
                        JSONObject().put("name", "projects/p/databases/(default)/documents" + p.removePrefix("/v1/projects/p/databases/(default)/documents")).put("fields", JSONObject(b).getJSONObject("fields"))
                    }
                    reply(200, if (docs.isEmpty()) "{}" else JSONObject().put("documents", org.json.JSONArray(docs)).toString())
                }
            }
        }
    }

    private fun backend() = FirestoreRestBackend(
        "p", "KEY", "http://127.0.0.1:${server.address.port}", "http://127.0.0.1:${server.address.port}", "http://127.0.0.1:${server.address.port}",
        clock = { now * 1000 }, timeoutMillis = 2_000,
    )

    private fun doc(expires: Long = now + 3600, idBytes: ByteArray = id) = RelayDocument(idBytes, tag, expires, envelopeBytes())

    private fun firestoreCalls() = requests.filter { it.path.startsWith("/v1/projects/") }

    // ── Écriture ─────────────────────────────────────────────────────────────

    @Test fun publishStoresOnlyTheOpaqueBytesAndTheExpiryUnderTheTag() = runBlocking {
        val b = backend()
        assertEquals(PublishResult.CREATED, b.publish(doc()))
        val call = firestoreCalls().single()
        assertEquals("POST", call.method)
        assertEquals("/v1/projects/p/databases/(default)/documents/relay/${"0a".repeat(16)}/e", call.path)
        assertEquals("documentId=${"0b".repeat(16)}", call.query)
        val fields = JSONObject(call.body).getJSONObject("fields")
        assertEquals("seuls b et x partent", setOf("b", "x"), fields.keys().asSequence().toSet())
        assertEquals(Base64.getEncoder().encodeToString(envelopeBytes()), fields.getJSONObject("b").getString("bytesValue"))
        assertEquals(Instant.ofEpochSecond(now + 3600).toString(), fields.getJSONObject("x").getString("timestampValue"))
    }

    @Test fun requestsCarryNoIdentifierBeyondTheAnonymousToken() = runBlocking {
        val b = backend()
        b.publish(doc()); b.fetch(tag, now, 10)
        val allowed = setOf("authorization", "content-type", "user-agent", "host", "accept", "connection", "content-length", "cache-control", "pragma")
        for (r in requests) {
            val extra = r.headers.keys - allowed
            assertTrue("en-tête inattendu ${extra} sur ${r.path}", extra.isEmpty())
            assertFalse("aucun cookie", "cookie" in r.headers)
        }
        // L'appel d'inscription n'envoie ni pseudo ni appareil : seulement la demande d'un jeton.
        assertEquals("""{"returnSecureToken":true}""", requests.first { it.path == "/v1/accounts:signUp" }.body)
    }

    @Test fun publishingTwiceIsIdempotent() = runBlocking {
        val b = backend()
        assertEquals(PublishResult.CREATED, b.publish(doc()))
        assertEquals(PublishResult.EXISTS, b.publish(doc()))
    }

    @Test fun rulesRefusalIsRejectedNotRetried() = runBlocking {
        val b = backend()
        firestoreStatus = 403
        assertEquals(PublishResult.REJECTED, b.publish(doc()))
        firestoreStatus = 400
        assertEquals(PublishResult.REJECTED, b.publish(doc()))
    }

    @Test fun serverErrorsAndQuotaAreRetryableFailures() = runBlocking {
        val b = backend()
        for (code in listOf(429, 500, 503)) { firestoreStatus = code; assertEquals(PublishResult.FAILED, b.publish(doc())) }
    }

    @Test fun unreachableServerIsAFailureNotAnException() = runBlocking {
        val b = backend()
        server.stop(0)
        assertEquals(PublishResult.FAILED, b.publish(doc()))
        assertFalse(b.fetch(tag, now, 10).ok)
    }

    // ── Lecture ──────────────────────────────────────────────────────────────

    @Test fun fetchReturnsStoredDocumentsAndNothingForUnknownTags() = runBlocking {
        val b = backend()
        b.publish(doc())
        val found = b.fetch(tag, now, 10).docs!!
        assertEquals(1, found.size)
        assertTrue(found.single().id.contentEquals(id))
        assertTrue(found.single().envelope.contentEquals(envelopeBytes()))
        assertEquals(now + 3600, found.single().expiresAtSeconds)
        assertTrue(b.fetch(ByteArray(16) { 0x33 }, now, 10).docs!!.isEmpty())
        val list = firestoreCalls().last()
        assertEquals("GET", list.method)
        assertEquals("pageSize=10", list.query)
    }

    @Test fun expiredAndMalformedDocumentsAreSkipped() = runBlocking {
        val good = Base64.getEncoder().encodeToString(envelopeBytes())
        fun d(name: String, b: String, x: String) = """{"name":"projects/p/databases/(default)/documents/relay/t/e/$name","fields":{"b":{"bytesValue":"$b"},"x":{"timestampValue":"$x"}}}"""
        val future = Instant.ofEpochSecond(now + 100).toString()
        val past = Instant.ofEpochSecond(now - 100).toString()
        listBody = """{"documents":[
            ${d("0b".repeat(16), good, future)},
            ${d("0c".repeat(16), good, past)},
            ${d("zz".repeat(16), good, future)},
            ${d("0d".repeat(16), "!!pas-du-base64!!", future)},
            ${d("0e".repeat(16), good, "hier")},
            ${d("0f".repeat(16), Base64.getEncoder().encodeToString(ByteArray(Envelope.MAX_SIZE + 1)), future)},
            {"name":"x"}
        ]}"""
        val docs = backend().fetch(tag, now, 50).docs!!
        assertEquals("un seul document valide parmi sept", 1, docs.size)
        assertEquals("0b".repeat(16), docs.single().idHex)
    }

    @Test fun garbageResponseIsAFailureNotACrash() = runBlocking {
        listBody = "ceci n'est pas du JSON"
        assertFalse(backend().fetch(tag, now, 10).ok)
    }

    @Test fun limitIsRespectedEvenIfTheServerSendsMore() = runBlocking {
        val good = Base64.getEncoder().encodeToString(envelopeBytes())
        val future = Instant.ofEpochSecond(now + 100).toString()
        val docs = (1..20).joinToString(",") { n -> """{"name":"x/e/${"%032x".format(n)}","fields":{"b":{"bytesValue":"$good"},"x":{"timestampValue":"$future"}}}""" }
        listBody = """{"documents":[$docs]}"""
        assertEquals(5, backend().fetch(tag, now, 5).docs!!.size)
    }

    // ── Authentification anonyme ─────────────────────────────────────────────

    @Test fun signsInAnonymouslyOnceAndReusesTheToken() = runBlocking {
        val b = backend()
        repeat(3) { b.fetch(tag, now, 10) }
        assertEquals(1, signUps)
        assertEquals(0, refreshes)
        assertTrue(firestoreCalls().all { it.headers["authorization"] == "Bearer tok1" })
        assertTrue("clé d'API passée en paramètre, comme l'exige Firebase", requests.first { it.path == "/v1/accounts:signUp" }.query!!.contains("key=KEY"))
    }

    @Test fun anExpiredTokenIsRefreshedWithoutCreatingANewAccount() = runBlocking {
        val b = backend()
        b.fetch(tag, now, 10)
        rejectTokensBelow = 2                                      // le serveur invalide tok1
        assertTrue(b.fetch(tag, now, 10).ok)
        assertEquals("pas de nouveau compte anonyme", 1, signUps)
        assertEquals(1, refreshes)
        assertEquals("Bearer tok2", firestoreCalls().last().headers["authorization"])
    }

    @Test fun neverAuthenticatedMeansFailureNotRequestsWithoutToken() = runBlocking {
        rejectTokensBelow = 99
        assertEquals(PublishResult.FAILED, backend().publish(doc()))
    }

    @Test fun noRedirectIsFollowed() = runBlocking {
        server.removeContext("/")
        server.createContext("/") { ex -> ex.responseHeaders.add("Location", "http://127.0.0.1:1/ailleurs"); ex.sendResponseHeaders(302, -1); ex.close() }
        assertNull(backend().fetch(tag, now, 10).docs)
    }
}
