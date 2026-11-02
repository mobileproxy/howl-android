package io.nekohasekai.sfa.update

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One native download at a time, including a cancelled call that has not returned yet. */
object CancellableDownload {
    private val busy = AtomicBoolean()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Howl-update").apply { isDaemon = true } }
    private val closer = Executors.newSingleThreadExecutor { Thread(it, "Howl-update-cancel").apply { isDaemon = true } }
    suspend fun <T> run(close: () -> Unit, work: () -> T): T = suspendCancellableCoroutine { continuation ->
        if (!busy.compareAndSet(false, true)) {
            continuation.resumeWithException(IllegalStateException("Previous download is still stopping"))
            return@suspendCancellableCoroutine
        }
        val closing = AtomicBoolean()
        val closeDone = java.util.concurrent.CountDownLatch(1)
        fun closeOnce() {
            if (closing.compareAndSet(false, true)) {
                try { close() } catch (_: Exception) { /* The download reports its own failure. */ }
                finally { closeDone.countDown() }
            }
        }
        continuation.invokeOnCancellation {
            closer.execute { closeOnce() }
        }
        worker.execute {
            try {
                if (continuation.isActive) {
                    val result = work()
                    if (continuation.isActive) continuation.resume(result)
                }
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            } finally {
                // A blocked native cancellation retains the slot; repeated clicks cannot create workers.
                closeOnce()
                closeDone.await()
                busy.set(false)
            }
        }
    }
}
