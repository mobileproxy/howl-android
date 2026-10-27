package io.nekohasekai.sfa.bg

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WatchdogProbeRunnerTest {
    @Test fun ordinarySuccessAndNetworkFailureAreDistinctFromProbeErrors() = runBlocking {
        val runner = WatchdogProbeRunner()
        assertEquals(WatchdogProbeResult.Reachable, runner.run(500) { true })
        assertEquals(WatchdogProbeResult.NoResponse, runner.run(500) { false })
        assertEquals(WatchdogProbeResult.NoResponse, runner.run(500) { throw IOException("fixture") })
        assertEquals(WatchdogProbeResult.Unavailable, runner.run(500) { error("fixture") })
    }

    @Test fun uncooperativeBlockCannotHoldDeadlineOrReportLateSuccess() = runBlocking {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val started = System.nanoTime()
        try {
            val result = WatchdogProbeRunner(cancellationGraceMs = 20).run(60) {
                entered.countDown(); release.await(2, TimeUnit.SECONDS); true
            }
            assertEquals(0L, entered.count)
            assertEquals(WatchdogProbeResult.Unavailable, result)
            assertTrue("Coroutine waited for blocking code", (System.nanoTime() - started) / 1_000_000 < 700)
        } finally { release.countDown() }
    }

    @Test fun deadlineClosesOwnedResourceAndCooperativeWorkStops() = runBlocking {
        val released = CountDownLatch(1)
        val closes = AtomicInteger()
        val result = WatchdogProbeRunner(cancellationGraceMs = 300).run(60) { resources ->
            resources.own(Closeable { closes.incrementAndGet(); released.countDown() })
            released.await(2, TimeUnit.SECONDS); false
        }
        assertEquals(WatchdogProbeResult.NoResponse, result)
        assertEquals(0L, released.count)
        assertEquals(1, closes.get())
    }

    @Test fun callerCancellationClosesOnlyThisCallsResource() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val closed = CountDownLatch(1); val external = AtomicInteger()
        val runner = WatchdogProbeRunner()
        val job = launch {
            runner.run(2000) { resources ->
                resources.own(Closeable { release.countDown(); closed.countDown() })
                entered.complete(Unit); release.await(3, TimeUnit.SECONDS); true
            }
            external.incrementAndGet()
        }
        try {
            withTimeout(3000) { entered.await() }
            job.cancelAndJoin()
            assertTrue(withContext(Dispatchers.IO) { closed.await(2, TimeUnit.SECONDS) })
            assertEquals(0, external.get())
            assertEquals(WatchdogProbeResult.Reachable, runner.run(1000) { true })
        } finally { release.countDown(); job.cancelAndJoin() }
    }

    @Test fun resourceCreatedAfterCancellationIsClosedInsteadOfUsed() = runBlocking {
        val release = CountDownLatch(1); val lateClosed = CountDownLatch(1)
        val entered = CompletableDeferred<Unit>(); var used = false
        val job = launch {
            WatchdogProbeRunner().run(2000) { resources ->
                entered.complete(Unit); release.await(3, TimeUnit.SECONDS)
                resources.own(Closeable { lateClosed.countDown() }); used = true; true
            }
        }
        try {
            withTimeout(3000) { entered.await() }; job.cancelAndJoin(); release.countDown()
            assertTrue(withContext(Dispatchers.IO) { lateClosed.await(2, TimeUnit.SECONDS) })
            assertFalse(used)
        } finally { release.countDown(); job.cancelAndJoin() }
    }

    @Test fun saturatedExecutorDoesNotQueueMoreBlockingOperations() = runBlocking {
        val runner = WatchdogProbeRunner(cancellationGraceMs = 10)
        val entered = CountDownLatch(4); val release = CountDownLatch(1); val calls = AtomicInteger()
        val jobs = (1..4).map { async(Dispatchers.Default) {
            runner.run(2000) { calls.incrementAndGet(); entered.countDown(); release.await(3, TimeUnit.SECONDS); false }
        } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            repeat(12) { assertEquals(WatchdogProbeResult.Unavailable, runner.run(100) { calls.incrementAndGet(); true }) }
            assertEquals(4, calls.get())
        } finally { release.countDown(); jobs.awaitAll() }
    }

    @Test fun rejectedExecutionIsUnavailableWithoutStartingWork() = runBlocking {
        var called = false
        val result = WatchdogProbeRunner(workers = Executor { throw java.util.concurrent.RejectedExecutionException() })
            .run(100) { called = true; true }
        assertEquals(WatchdogProbeResult.Unavailable, result)
        assertFalse(called)
    }
}
