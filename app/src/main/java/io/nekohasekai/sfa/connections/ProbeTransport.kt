package io.nekohasekai.sfa.connections

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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

/** Blocking gomobile calls never occupy Main. Only a private client may be disconnected. */
class ProbeTransport(private val factory: () -> OwnedProbeClient,
    private val workers: Executor = pool, private val closers: Executor = closePool) {
    companion object {
        private val pool = Executors.newFixedThreadPool(4) { Thread(it, "Howl connection probe").apply { isDaemon = true } }
        private val closePool = Executors.newFixedThreadPool(4) { Thread(it, "Howl probe cancellation").apply { isDaemon = true } }
    }
    suspend fun measure(tag: String, attempt: Int): Int? {
        val timeout = if (attempt == 1) 6000 else 4000
        for (url in ConnectionChecks.urls) {
            val reply = withTimeoutOrNull(timeout + 500L) { request(tag, url, timeout) }
            if (reply != null && !reply.failed) {
                check(reply.delayMs >= 0)
                return reply.delayMs
            }
        }
        return null
    }
    private suspend fun request(tag: String, url: String, timeout: Int): ProbeReply = suspendCancellableCoroutine { continuation ->
        val client = AtomicReference<OwnedProbeClient?>()
        val cancelled = AtomicBoolean()
        fun closeAsync() {
            val owned = client.getAndSet(null) ?: return
            closers.execute { runCatching { owned.close() } }
        }
        continuation.invokeOnCancellation { cancelled.set(true); closeAsync() }
        workers.execute {
            if (cancelled.get()) return@execute
            try {
                val owned = factory()
                client.set(owned)
                if (cancelled.get()) { closeAsync(); return@execute }
                val reply = owned.test(tag, url, timeout)
                if (continuation.isActive) continuation.resume(reply)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally { closeAsync() }
        }
    }
}
