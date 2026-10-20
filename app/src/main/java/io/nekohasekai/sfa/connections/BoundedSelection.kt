package io.nekohasekai.sfa.connections

import java.util.concurrent.Executor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface OwnedSelectionClient : java.io.Closeable { fun select(group: String, tag: String) }

/** A private command connection is separate from application data connections. */
class BoundedSelection(private val factory: () -> OwnedSelectionClient,
    private val workers: Executor = pool,
    private val closers: Executor = closePool, private val timeoutMs: Long = 5000) {
    companion object {
        // One native selection at a time, at most one queued request. A stuck
        // native call cannot accumulate threads or an unbounded command queue.
        private val pool = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
            { work -> Thread(work, "Howl route selection").apply { isDaemon = true } })
        private val closePool = Executors.newFixedThreadPool(2) { work -> Thread(work, "Howl selection cancellation").apply { isDaemon = true } }
    }
    init { require(timeoutMs > 0) }
    suspend fun select(group: String, tag: String, stillCurrent: () -> Boolean) {
        val confirmed = withTimeoutOrNull(timeoutMs) { request(group, tag, stillCurrent); true }
        check(confirmed == true) { "Route selection was not confirmed" }
    }
    private suspend fun request(group: String, tag: String, stillCurrent: () -> Boolean): Unit = suspendCancellableCoroutine { continuation ->
        val cancelled = AtomicBoolean()
        val client = AtomicReference<OwnedSelectionClient?>()
        fun closeOwned() {
            val owned = client.getAndSet(null) ?: return
            closers.execute { runCatching { owned.close() } }
        }
        val work = Runnable {
            if (cancelled.get()) return@Runnable
            try {
                check(stillCurrent()) { "Selection was superseded" }
                val owned = factory()
                client.set(owned)
                if (cancelled.get()) return@Runnable
                check(stillCurrent()) { "Selection was superseded" }
                owned.select(group, tag)
                if (continuation.isActive) continuation.resume(Unit)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            } finally { closeOwned() }
        }
        continuation.invokeOnCancellation {
            cancelled.set(true)
            if (workers is ThreadPoolExecutor) workers.remove(work)
            closeOwned()
        }
        try { workers.execute(work) }
        catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
        if (cancelled.get() && workers is ThreadPoolExecutor) workers.remove(work)
    }
}
