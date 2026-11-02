package io.nekohasekai.sfa.update

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CancellableDownloadTest {
    @Test fun cancellationReturnsPromptlyButRetainsAdmissionUntilNativeWorkerStops() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val closed = AtomicBoolean(); var installed = false
        val task = launch(Dispatchers.Default) {
            CancellableDownload.run(close = { closed.set(true) }) {
                entered.countDown(); release.await(5, TimeUnit.SECONDS); "apk"
            }
            ensureActive(); installed = true
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        try {
            withTimeout(1000) { task.cancelAndJoin() }
            withTimeout(1000) { while (!closed.get()) delay(1) }
            try { CancellableDownload.run(close = {}) { error("must not start") }; fail("admission opened") }
            catch (_: IllegalStateException) {}
            assertFalse(installed)
        } finally { release.countDown() }
        withTimeout(2000) {
            while (true) {
                try { assertEquals("next", CancellableDownload.run(close = {}) { "next" }); break }
                catch (_: IllegalStateException) { delay(10) }
            }
        }
    }
}
