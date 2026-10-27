package io.nekohasekai.sfa.connections

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProbeTransportTest {
    @Test fun stalledCommandCleanupCannotAccumulateUnlimitedOwnedClientsAcrossTransports() = runBlocking {
        val pendingCloses = mutableListOf<Runnable>()
        val factories = AtomicInteger()
        val workers = java.util.concurrent.Executor { it.run() }
        val closers = java.util.concurrent.Executor { pendingCloses.add(it) }
        fun transport() = ProbeTransport(factory = { factories.incrementAndGet(); object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int) = ProbeReply(10, false)
            override fun close() {}
        } }, workers = workers, closers = closers, firstTimeoutMs = 20, retryTimeoutMs = 20,
            requestSlackMs = 10, cancellationGraceMs = 20)
        val first = transport(); val second = transport()
        var busy = 0
        try {
            repeat(32) { index ->
                try { assertEquals(10, (if (index % 2 == 0) first else second).measure("fixture", 1)) }
                catch (_: ProbeRequestNotStartedException) { busy++ }
            }
            assertTrue("Owned command clients grew without bound", factories.get() <= 12)
            assertTrue(busy > 0)
            assertEquals(factories.get(), pendingCloses.size)
        } finally { pendingCloses.forEach(Runnable::run) }
        assertEquals(10, transport().measure("fixture", 1))
        pendingCloses.last().run()
    }
    @Test fun missingCoreTargetIsAnErrorWithoutItsRawTag() {
        val error = runCatching { ProbeReply.fromCore(0, "outbound or endpoint not found: private-fixture") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(error!!.message.orEmpty().contains("private-fixture"))
    }
    @Test fun coreHandshakeFailureIsNotGuessedAsBlocking() {
        assertTrue(ProbeReply.fromCore(0, "fixture handshake failure").failed)
        assertEquals(ProbeReply(0, false), ProbeReply.fromCore(0, ""))
    }
    @Test fun firstPassAndIndependentConfirmationSupportZeroLatency() = runBlocking {
        val urls = mutableListOf<String>(); val timeouts = mutableListOf<Int>(); val closed = AtomicInteger()
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply {
                assertEquals("node / +", tag); urls.add(url); timeouts.add(timeoutMs)
                return ProbeReply(0, urls.size == 1)
            }
            override fun close() { closed.incrementAndGet() }
        } })
        assertNull(transport.measure("node / +", 1))
        assertEquals(0, transport.measure("node / +", 2))
        assertEquals(ConnectionChecks.urls, urls); assertEquals(listOf(4500, 4000), timeouts)
        withTimeout(5000) { while (closed.get() != 2) delay(1) }
    }
    @Test fun confirmationUsesFourSecondsAndExhaustsBothTargets() = runBlocking {
        val timeouts = mutableListOf<Int>()
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply { timeouts.add(timeoutMs); return ProbeReply(0, true) }
            override fun close() {}
        } })
        assertNull(transport.measure("node", 2)); assertEquals(listOf(4000, 4000), timeouts)
    }
    @Test fun transportExceptionDoesNotBecomeNoResponse() = runBlocking {
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply = error("fixture transport error")
            override fun close() {}
        } })
        var error = false
        try { transport.measure("node", 1) } catch (_: IllegalStateException) { error = true }
        assertTrue(error)
    }
    @Test fun negativeSuccessfulReplyIsNotAccepted() = runBlocking {
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int) = ProbeReply(-1, false)
            override fun close() {}
        } })
        var error = false
        try { transport.measure("node", 1) } catch (_: IllegalStateException) { error = true }
        assertTrue(error)
    }
    @Test fun cancellationClosesOnlyOwnedClientAndIgnoresLateSuccess() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val closed = CountDownLatch(1); val release = CountDownLatch(1)
        val closeCount = AtomicInteger(); var completed = false
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply {
                entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)); return ProbeReply(80, false)
            }
            override fun close() { closeCount.incrementAndGet(); release.countDown(); closed.countDown() }
        } })
        val job = launch { transport.measure("node", 1); completed = true }
        withTimeout(5000) { entered.await() }; job.cancelAndJoin()
        assertTrue(withContext(Dispatchers.IO) { closed.await(5, TimeUnit.SECONDS) })
        assertEquals(1, closeCount.get()); assertFalse(completed)
    }
    @Test fun cancelledQueuedCallDoesNotCreateAClient() = runBlocking {
        val pending = mutableListOf<Runnable>(); var factories = 0
        val transport = ProbeTransport(factory = { factories++; error("must not create") }, workers = java.util.concurrent.Executor { pending.add(it) })
        val job = launch { transport.measure("node", 1) }
        yield(); job.cancelAndJoin(); pending.forEach(Runnable::run)
        assertEquals(0, factories)
    }

    @Test fun configuredHealthTargetsUseHttpsWithoutChangingManualFirstAndRetryPolicy() = runBlocking {
        val requested = mutableListOf<String>()
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply {
                requested.add(url); return ProbeReply(0, requested.size == 1)
            }
            override fun close() {}
        } }, workers = java.util.concurrent.Executor { it.run() }, closers = java.util.concurrent.Executor { it.run() },
            coordinator = ProbeCoordinator(), priority = ProbePriority.Active, controlUrls = ProbeTransport.HEALTH_URLS)
        assertNull(transport.measure("fixture", 1))
        assertEquals(0, transport.measure("fixture", 2))
        assertEquals(listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204"), requested)
    }

    @Test fun admissionWaitHappensBeforeWorkerSubmissionAndCancellationCreatesNoClient() = runBlocking {
        val coordinator = ProbeCoordinator(); val owners = List(6) { coordinator.acquire(ProbePriority.ManualScan) }
        val submissions = AtomicInteger(); val factories = AtomicInteger()
        val transport = ProbeTransport(factory = { factories.incrementAndGet(); error("must not create") },
            workers = java.util.concurrent.Executor { submissions.incrementAndGet(); it.run() }, coordinator = coordinator)
        val job = launch { runCatching { transport.measure("fixture", 1) } }
        yield()
        try { assertEquals(0, submissions.get()); job.cancelAndJoin(); assertEquals(0, factories.get()) }
        finally { job.cancelAndJoin(); owners.forEach { it.close() } }
    }

    @Test fun successfulTestRetainsAdmissionUntilItsPrivateDisconnectReallyFinishes() = runBlocking {
        val coordinator = ProbeCoordinator(); val pendingClose = mutableListOf<Runnable>(); val closed = AtomicInteger()
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int) = ProbeReply(10, false)
            override fun close() { closed.incrementAndGet() }
        } }, workers = java.util.concurrent.Executor { it.run() }, closers = java.util.concurrent.Executor { pendingClose.add(it) },
            priority = ProbePriority.Active, coordinator = coordinator)
        assertEquals(10, transport.measure("fixture", 1))
        val others = List(7) { coordinator.acquire(ProbePriority.Active) }
        val blocked = async { coordinator.acquire(ProbePriority.Active) }; yield()
        try {
            assertFalse(blocked.isCompleted); pendingClose.single().run()
            withTimeout(5000) { blocked.await().close() }; assertEquals(1, closed.get())
        } finally { blocked.cancelAndJoin(); pendingClose.forEach { it.run() }; others.forEach { it.close() } }
        assertEquals(1, closed.get())
    }

    @Test fun cancellationAndCompletedDisconnectDoNotReleaseAStillRunningNativeTest() = runBlocking {
        val coordinator = ProbeCoordinator(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val closed = CountDownLatch(1); val closeCount = AtomicInteger(); val workers = java.util.concurrent.Executors.newSingleThreadExecutor()
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return ProbeReply(10, false)
            }
            override fun close() { closeCount.incrementAndGet(); closed.countDown() }
        } }, workers = workers, priority = ProbePriority.Active, coordinator = coordinator)
        val job = launch { transport.measure("fixture", 1) }
        val others = List(7) { coordinator.acquire(ProbePriority.Active) }
        var blocked: Deferred<OwnedProbeLease>? = null
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            job.cancelAndJoin(); assertTrue(withContext(Dispatchers.IO) { closed.await(5, TimeUnit.SECONDS) })
            blocked = async { coordinator.acquire(ProbePriority.Active) }; yield()
            assertFalse(blocked.isCompleted); release.countDown()
            withTimeout(5000) { blocked.await().close() }; assertEquals(1, closeCount.get())
        } finally {
            release.countDown(); job.cancelAndJoin(); blocked?.cancelAndJoin(); others.forEach { it.close() }
            workers.shutdown(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun cancelledHungFactoryRetainsOwnershipAndClosesLateClientWithoutTestingIt() = runBlocking {
        val coordinator = ProbeCoordinator(); val factoryEntered = CountDownLatch(1); val releaseFactory = CountDownLatch(1)
        val closeReady = CountDownLatch(1); val pending = java.util.concurrent.atomic.AtomicReference<Runnable>()
        val tests = AtomicInteger(); val closes = AtomicInteger(); val workers = java.util.concurrent.Executors.newSingleThreadExecutor()
        val transport = ProbeTransport(factory = {
            factoryEntered.countDown(); check(releaseFactory.await(5, TimeUnit.SECONDS))
            object : OwnedProbeClient {
                override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply { tests.incrementAndGet(); return ProbeReply(10, false) }
                override fun close() { closes.incrementAndGet() }
            }
        }, workers = workers, closers = java.util.concurrent.Executor { pending.set(it); closeReady.countDown() },
            priority = ProbePriority.Active, coordinator = coordinator)
        val job = launch { transport.measure("fixture", 1) }; val others = List(7) { coordinator.acquire(ProbePriority.Active) }
        var blocked: Deferred<OwnedProbeLease>? = null
        try {
            assertTrue(withContext(Dispatchers.IO) { factoryEntered.await(5, TimeUnit.SECONDS) })
            job.cancelAndJoin(); blocked = async { coordinator.acquire(ProbePriority.Active) }; yield()
            assertFalse(blocked.isCompleted); releaseFactory.countDown()
            assertTrue(withContext(Dispatchers.IO) { closeReady.await(5, TimeUnit.SECONDS) }); yield()
            assertFalse(blocked.isCompleted); assertEquals(0, tests.get()); pending.get().run()
            withTimeout(5000) { blocked.await().close() }; assertEquals(1, closes.get())
        } finally {
            releaseFactory.countDown(); job.cancelAndJoin(); blocked?.cancelAndJoin(); pending.get()?.run(); others.forEach { it.close() }
            workers.shutdown(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun rejectedWorkerIsTypedUnavailableAndReleasesAdmissionExactlyOnce() = runBlocking {
        val coordinator = ProbeCoordinator(); val factories = AtomicInteger()
        val transport = ProbeTransport(factory = { factories.incrementAndGet(); error("must not create") },
            workers = java.util.concurrent.Executor { throw java.util.concurrent.RejectedExecutionException() },
            priority = ProbePriority.Active, coordinator = coordinator)
        assertTrue(runCatching { transport.measure("fixture", 1) }.exceptionOrNull() is ProbeRequestNotStartedException)
        val all = withTimeout(5000) { List(8) { coordinator.acquire(ProbePriority.Active) } }
        all.forEach { it.close() }; assertEquals(0, factories.get())
    }

    @Test fun rejectedCloserFallsBackOnNativeWorkerWithoutLosingClientOrDoubleClose() = runBlocking {
        val coordinator = ProbeCoordinator(); val closes = AtomicInteger(); val ended = CountDownLatch(1)
        val workers = java.util.concurrent.Executors.newSingleThreadExecutor(); val caller = Thread.currentThread()
        val wrongThread = java.util.concurrent.atomic.AtomicBoolean()
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int) = ProbeReply(10, false)
            override fun close() { wrongThread.set(Thread.currentThread() === caller); closes.incrementAndGet(); ended.countDown() }
        } }, workers = workers, closers = java.util.concurrent.Executor { throw java.util.concurrent.RejectedExecutionException() },
            priority = ProbePriority.Active, coordinator = coordinator)
        try {
            assertEquals(10, transport.measure("fixture", 1))
            assertTrue(withContext(Dispatchers.IO) { ended.await(5, TimeUnit.SECONDS) })
            val all = withTimeout(5000) { List(8) { coordinator.acquire(ProbePriority.Active) } }
            all.forEach { it.close() }; assertEquals(1, closes.get()); assertFalse(wrongThread.get())
        } finally { workers.shutdown(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)) }
    }
}
