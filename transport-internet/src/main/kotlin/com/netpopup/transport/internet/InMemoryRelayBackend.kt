package com.netpopup.transport.internet

/**
 * Relais en mémoire : pour les tests et les aperçus (comme `InMemoryChatRepository`). Reproduit ce que fait le vrai
 * relais — rangement par étiquette, idempotence, expiration — et permet de simuler une panne.
 */
class InMemoryRelayBackend : RelayBackend {
    private val byTag = LinkedHashMap<String, LinkedHashMap<String, RelayDocument>>()

    /** Vrai : toutes les opérations échouent comme un réseau coupé. */
    @Volatile var offline: Boolean = false

    /** Nombre d'appels, pour vérifier ce que le téléphone interroge (et ce qu'il ne devrait pas). */
    @Volatile var publishCalls = 0
    val fetchedTags = java.util.Collections.synchronizedList(ArrayList<String>())

    private val lock = Any()

    override suspend fun publish(doc: RelayDocument): PublishResult = synchronized(lock) {
        publishCalls++
        if (offline) return@synchronized PublishResult.FAILED
        val bucket = byTag.getOrPut(doc.tagHex) { LinkedHashMap() }
        if (doc.idHex in bucket) return@synchronized PublishResult.EXISTS
        bucket[doc.idHex] = doc
        PublishResult.CREATED
    }

    override suspend fun fetch(destTag: ByteArray, nowSeconds: Long, limit: Int): FetchResult = synchronized(lock) {
        fetchedTags += destTag.toHex()
        if (offline) return@synchronized FetchResult(null)
        val docs = byTag[destTag.toHex()]?.values.orEmpty().filter { it.expiresAtSeconds > nowSeconds }.take(limit)
        FetchResult(docs)
    }

    /** Ce que le relais contient, tel que le verrait son exploitant : étiquettes, identifiants, tailles, dates. */
    fun everything(): List<RelayDocument> = synchronized(lock) { byTag.values.flatMap { it.values } }

    fun count(): Int = everything().size
}
