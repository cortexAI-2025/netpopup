package com.netpopup.dtn.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lazy, single-flight initialization. Only success is cached; failure/cancellation can be retried. */
class RetryableResource<T : Any>(private val create: suspend () -> T) {
    private val mutex = Mutex()
    @Volatile private var value: T? = null

    suspend fun get(): T = value ?: mutex.withLock {
        value ?: create().also { value = it }
    }

    /** Stopping an unused runtime must not create its database or identity. */
    fun peek(): T? = value
}
