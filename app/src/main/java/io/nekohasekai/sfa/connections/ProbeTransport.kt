package io.nekohasekai.sfa.connections

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ProbeReply(val delayMs: Int, val failed: Boolean) {
    companion object {
        // The pinned core reports a removed target using this exact control-error prefix.
        // Never export its tag or inspect arbitrary dial-error text to guess the cause.
        fun fromCore(delayMs: Int, error: String): ProbeReply {
            check(!error.startsWith("outbound or endpoint not found: ")) { "Probe target no longer exists" }
            return ProbeReply(delayMs, error.isNotEmpty())
        }
    }
}
interface OwnedProbeClient : java.io.Closeable {
    fun test(tag: String, url: String, timeoutMs: Int): ProbeReply
}

class ProbeRequestDidNotStopException : IllegalStateException("Probe request did not stop")
class ProbeRequestNotStartedException : IllegalStateException("Probe executor is busy")

/** Blocking gomobile calls never occupy Main. Only a private client may be disconnected. */
class ProbeTransport(private val factory: () -> OwnedProbeClient,
    private val workers: Executor = pool, private val closers: Executor = closePool,
    private val firstTimeoutMs: Int = 4500, private val retryTimeoutMs: Int = 4000,
    private val requestSlackMs: Long = 250, private val cancellationGraceMs: Long = 100) {
    companion object {
        // Shared across screens/runs: a cancelled native call still occupies its worker.
        private val pool = Executors.newFixedThreadPool(ConnectionChecks.PARALLELISM) { Thread(it, "Howl connection probe").apply { isDaemon = true } }
        private val closePool = Executors.newFixedThreadPool(4) { Thread(it, "Howl probe cancellation").apply { isDaemon = true } }
    }
    init {
        require(firstTimeoutMs > 0 && retryTimeoutMs > 0 && requestSlackMs >= 0 && cancellationGraceMs > 0)
        require(firstTimeoutMs + requestSlackMs + cancellationGraceMs < ConnectionChecks.FIRST_ATTEMPT_MS)
        require(2 * (retryTimeoutMs + requestSlackMs + cancellationGraceMs) < ConnectionChecks.RETRY_ATTEMPT_MS)
    }
    private class Call {
        val started = AtomicBoolean()
        val returned = CompletableDeferred<Unit>()
        val error = AtomicReference<Exception?>()
    }
    suspend fun measure(tag: String, attempt: Int): Int? {
        require(attempt in 1..2)
        val timeout = if (attempt == 1) firstTimeoutMs else retryTimeoutMs
        val budget = if (attempt == 1) ConnectionChecks.FIRST_ATTEMPT_MS else ConnectionChecks.RETRY_ATTEMPT_MS
        return withTimeoutOrNull(budget) {
            val urls = if (attempt == 1) ConnectionChecks.urls.take(1) else ConnectionChecks.urls.reversed()
            for (url in urls) {
                val call = Call()
                val reply = withTimeoutOrNull(timeout + requestSlackMs) { request(tag, url, timeout, call) }
                if (reply == null) {
                    // A queued call that never ran, or an uncooperative native call,
                    // cannot establish that this server failed to answer.
                    if (!call.started.get()) throw ProbeRequestNotStartedException()
                    withTimeoutOrNull(cancellationGraceMs) { call.returned.await() }
                    if (!call.returned.isCompleted) throw ProbeRequestDidNotStopException()
                    call.error.get()?.let { throw it }
                } else if (!reply.failed) {
                    check(reply.delayMs >= 0)
                    return@withTimeoutOrNull reply.delayMs
                }
            }
            null
        }
    }
    private suspend fun request(tag: String, url: String, timeout: Int, call: Call): ProbeReply = suspendCancellableCoroutine { continuation ->
        val client = AtomicReference<OwnedProbeClient?>()
        val cancelled = AtomicBoolean()
        fun closeAsync() {
            val owned = client.getAndSet(null) ?: return
            closers.execute { runCatching { owned.close() } }
        }
        val work = Runnable {
            if (cancelled.get()) { call.returned.complete(Unit); return@Runnable }
            call.started.set(true)
            try {
                val owned = factory()
                client.set(owned)
                if (cancelled.get()) return@Runnable
                val reply = owned.test(tag, url, timeout)
                if (continuation.isActive) continuation.resume(reply)
            } catch (error: Exception) {
                call.error.set(error)
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally { call.returned.complete(Unit); closeAsync() }
        }
        continuation.invokeOnCancellation {
            cancelled.set(true)
            if (workers is ThreadPoolExecutor && workers.remove(work)) call.returned.complete(Unit)
            closeAsync()
        }
        workers.execute(work)
        if (cancelled.get() && workers is ThreadPoolExecutor && workers.remove(work)) call.returned.complete(Unit)
    }
}
