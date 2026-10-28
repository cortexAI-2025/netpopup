package com.netpopup.dtn.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RetryableResourceTest {
    @Test fun concurrentCallersShareOneSuccessfulInitialization() = runTest {
        var attempts = 0
        val ready = CompletableDeferred<Unit>()
        val instance = Any()
        val resource = RetryableResource { attempts++; ready.await(); instance }
        assertNull(resource.peek())
        val callers = List(12) { async { resource.get() } }
        runCurrent()
        assertEquals(1, attempts)
        ready.complete(Unit)
        callers.forEach { assertSame(instance, it.await()) }
        assertSame(instance, resource.get())
        assertEquals(1, attempts)
    }

    @Test fun failedInitializationCanBeRetriedWithoutCachingFailure() = runTest {
        var attempts = 0
        val resource = RetryableResource {
            if (++attempts == 1) throw IllegalStateException("database unavailable")
            "ready"
        }
        try { resource.get(); fail("expected failure") } catch (_: IllegalStateException) { }
        assertNull(resource.peek())
        assertEquals("ready", resource.get())
        assertEquals(2, attempts)
    }

    @Test fun cancellingFirstCallerDoesNotPoisonLaterInitialization() = runTest {
        var attempts = 0
        val ready = CompletableDeferred<Unit>()
        val resource = RetryableResource { attempts++; ready.await(); "ready" }
        val first = async { resource.get() }
        runCurrent()
        first.cancelAndJoin()
        ready.complete(Unit)
        assertEquals("ready", resource.get())
        assertEquals(2, attempts)
    }
}
