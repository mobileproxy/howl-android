package io.nekohasekai.sfa.connections

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
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
    private val requestSlackMs: Long = 250, private val cancellationGraceMs: Long = 100,
    controlUrls: List<String> = ConnectionChecks.urls,
    private val priority: ProbePriority = ProbePriority.ManualScan,
    private val coordinator: ProbeCoordinator = ProbeCoordinator.shared) {
    companion object {
        val HEALTH_URLS: List<String> = listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")
        // Shared zero-queue pools: uncooperative native work/cleanup keeps a real worker.
        private fun pool(name: String) = ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS,
            SynchronousQueue(), { Thread(it, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        private val pool = pool("Howl connection probe")
        private val closePool = pool("Howl probe cancellation")
        // A returned native test may still have a blocked disconnect. Keep its
        // ownership permit until disconnect really ends, across all screens/runs.
        private val ownedClients = Semaphore(12)
    }
    private val urls = controlUrls.toList()
    init {
        require(urls.isNotEmpty() && urls.all { it.isNotBlank() })
        require(firstTimeoutMs > 0 && retryTimeoutMs > 0 && requestSlackMs >= 0 && cancellationGraceMs > 0)
        require(firstTimeoutMs + requestSlackMs + cancellationGraceMs < ConnectionChecks.FIRST_ATTEMPT_MS)
        require(2 * (retryTimeoutMs + requestSlackMs + cancellationGraceMs) < ConnectionChecks.RETRY_ATTEMPT_MS)
    }
    private class Call {
        val started = AtomicBoolean()
        val returned = CompletableDeferred<Unit>()
        val error = AtomicReference<Exception?>()
    }
    private data class Measurement(val delayMs: Int?)
    suspend fun measure(tag: String, attempt: Int): Int? {
        require(attempt in 1..2)
        val timeout = if (attempt == 1) firstTimeoutMs else retryTimeoutMs
        val budget = if (attempt == 1) ConnectionChecks.FIRST_ATTEMPT_MS else ConnectionChecks.RETRY_ATTEMPT_MS
        val measurement = withTimeoutOrNull(budget) {
            val targets = if (attempt == 1) urls.take(1) else urls.reversed()
            for (url in targets) {
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
                    return@withTimeoutOrNull Measurement(reply.delayMs)
                }
            }
            Measurement(null)
        } ?: throw ProbeRequestDidNotStopException()
        return measurement.delayMs
    }
    private suspend fun request(tag: String, url: String, timeout: Int, call: Call): ProbeReply {
        val lease = try { coordinator.acquire(priority) }
        catch (_: ProbeAdmissionBusyException) { throw ProbeRequestNotStartedException() }
        return suspendCancellableCoroutine { continuation ->
            val lock = Any()
            val claimed = AtomicBoolean()
            var cancelled = false
            var client: OwnedProbeClient? = null
            var ownsClientPermit = false
            var workReturned = false
            var closeStarted = false
            var closeFinished = false
            var released = false

            fun releaseFinishedOwnership() {
                var releaseClientPermit = false
                val releaseLease = synchronized(lock) {
                    if (released || !workReturned || (client != null && !closeFinished)) false
                    else { released = true; releaseClientPermit = ownsClientPermit; ownsClientPermit = false; true }
                }
                if (releaseClientPermit) ownedClients.release()
                if (releaseLease) lease.close()
            }

            // Caller holds lock while claiming/submitting close. On rejection only
            // the originating native worker may close inline; never cancellation/Main.
            fun submitClose(fromWorker: Boolean): Runnable? {
                val owned = client ?: return null
                if (closeStarted) return null
                closeStarted = true
                val executed = AtomicBoolean()
                val close = Runnable {
                    if (!executed.compareAndSet(false, true)) return@Runnable
                    try { runCatching { owned.close() } }
                    finally {
                        synchronized(lock) { closeFinished = true }
                        releaseFinishedOwnership()
                    }
                }
                try { closers.execute(close) }
                catch (_: RejectedExecutionException) {
                    if (fromWorker) return close
                    closeStarted = false // The running worker will retry/fall back on return.
                }
                return null
            }

            val work = Runnable {
                if (!claimed.compareAndSet(false, true)) return@Runnable
                try {
                    if (synchronized(lock) { cancelled }) return@Runnable
                    call.started.set(true)
                    if (!ownedClients.tryAcquire()) throw ProbeRequestNotStartedException()
                    synchronized(lock) { ownsClientPermit = true }
                    val owned = factory()
                    synchronized(lock) { client = owned }
                    if (synchronized(lock) { cancelled }) return@Runnable
                    val reply = owned.test(tag, url, timeout)
                    if (continuation.isActive) continuation.resume(reply)
                } catch (error: Exception) {
                    call.error.set(error)
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    call.returned.complete(Unit)
                    val inlineClose = synchronized(lock) { workReturned = true; submitClose(fromWorker = true) }
                    inlineClose?.run()
                    releaseFinishedOwnership()
                }
            }
            continuation.invokeOnCancellation {
                synchronized(lock) { cancelled = true; submitClose(fromWorker = false) }
                if (claimed.compareAndSet(false, true)) {
                    // The pending runnable is inert even for injected executors without remove().
                    if (workers is ThreadPoolExecutor) workers.remove(work)
                    synchronized(lock) { workReturned = true }
                    call.returned.complete(Unit)
                    releaseFinishedOwnership()
                }
            }
            try { workers.execute(work) }
            catch (_: RejectedExecutionException) {
                if (claimed.compareAndSet(false, true)) {
                    val error = ProbeRequestNotStartedException()
                    call.error.set(error)
                    synchronized(lock) { workReturned = true }
                    call.returned.complete(Unit)
                    releaseFinishedOwnership()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    }
}
