package io.nekohasekai.sfa.bg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

enum class WatchdogProbeResult { Reachable, NoResponse, Unavailable }
class WatchdogProbeUnavailableException : IllegalStateException("Watchdog probe unavailable")

/** A deadline never waits for blocking DNS/IO. A stuck call keeps its real worker. */
class WatchdogProbeRunner(
    private val workers: Executor = pool,
    private val closers: Executor = closePool,
    private val cancellationGraceMs: Long = 100,
) {
    companion object {
        private val pool = ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, SynchronousQueue(),
            { work -> Thread(work, "Howl watchdog probe").apply { isDaemon = true } })
        private val closePool = ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
            { work -> Thread(work, "Howl watchdog cleanup").apply { isDaemon = true } })
    }
    init { require(cancellationGraceMs > 0) }

    suspend fun run(timeoutMs: Long, block: (WatchdogProbeResources) -> Boolean): WatchdogProbeResult {
        require(timeoutMs > 0)
        val returned = CompletableDeferred<Unit>()
        val resources = WatchdogProbeResources(closers)
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val cancelled = AtomicBoolean()
                val work = Runnable {
                    try {
                        if (cancelled.get()) return@Runnable
                        val outcome = try {
                            if (block(resources)) WatchdogProbeResult.Reachable else WatchdogProbeResult.NoResponse
                        } catch (_: IOException) { WatchdogProbeResult.NoResponse }
                        catch (_: Exception) { WatchdogProbeResult.Unavailable }
                        if (continuation.isActive) continuation.resume(outcome)
                    } finally {
                        // Holding the worker until cleanup ends also bounds stuck closes.
                        resources.finish()
                        returned.complete(Unit)
                    }
                }
                continuation.invokeOnCancellation {
                    cancelled.set(true)
                    resources.cancel()
                }
                try { workers.execute(work) }
                catch (_: Exception) {
                    returned.complete(Unit)
                    if (continuation.isActive) continuation.resume(WatchdogProbeResult.Unavailable)
                }
            }
        }
        if (result != null) return result
        // A non-cooperating worker is a failed check, not evidence of a dead server.
        withTimeoutOrNull(cancellationGraceMs) { returned.await() }
        return if (returned.isCompleted) WatchdogProbeResult.NoResponse else WatchdogProbeResult.Unavailable
    }
}

/** Only resources created for this one check may be registered here. */
class WatchdogProbeResources internal constructor(private val closers: Executor) {
    private val lock = Any()
    private val owned = mutableListOf<Closeable>()
    private var closing = false
    private val finished = CountDownLatch(1)
    private val deferredCleanup = AtomicReference<Runnable?>()

    fun <T : Closeable> own(resource: T): T {
        synchronized(lock) {
            if (!closing) { owned.add(resource); return resource }
        }
        // A late DNS/factory result runs on its already bounded worker.
        runCatching { resource.close() }
        throw IOException("Probe ended")
    }

    internal fun cancel() {
        synchronized(lock) {
            if (closing) return
            closing = true
            val snapshot = owned.asReversed().toList().also { owned.clear() }
            val cleanup = Runnable {
                try { snapshot.forEach { runCatching { it.close() } } }
                finally { finished.countDown() }
            }
            try { closers.execute(cleanup) }
            catch (_: Exception) { deferredCleanup.set(cleanup) }
        }
    }

    internal fun finish() {
        cancel()
        deferredCleanup.getAndSet(null)?.run()
        finished.await()
    }
}
