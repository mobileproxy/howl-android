package io.nekohasekai.sfa.connections

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class ProbeDeadlinesTest {
    /** Timer callbacks run on virtual time; continuation dispatch can deliberately lag. */
    private class PausedDispatcher(timer: CoroutineDispatcher) : CoroutineDispatcher(), Delay by (timer as Delay) {
        private val pending = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        fun drain() { while (true) (pending.poll() ?: return).run() }
    }

    @Test fun attemptDeadlineDuringCancellationGraceCannotBecomeNoResponseOrRetry() = runTest {
        val release = CountDownLatch(1); val entered = CountDownLatch(1); val calls = AtomicInteger()
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        val dispatcher = PausedDispatcher(StandardTestDispatcher(testScheduler))
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply {
                calls.incrementAndGet(); entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)); return ProbeReply(120, false)
            }
            override fun close() {}
        } }, workers = workers, firstTimeoutMs = 100, retryTimeoutMs = 100, requestSlackMs = 0, cancellationGraceMs = 100)
        val job = async(dispatcher) {
            ConnectionChecks.run(listOf("fixture"), transport::measure, {}, retryPauseMs = 0, attemptDeadlineMs = 150)
        }
        try {
            dispatcher.drain()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            advanceTimeBy(100); runCurrent(); dispatcher.drain() // Request timeout; grace lasts until 200.
            advanceTimeBy(50); runCurrent(); dispatcher.drain() // Attempt deadline at 150 interrupts grace.
            advanceTimeBy(1000); runCurrent(); dispatcher.drain()
            val final = job.await()
            assertEquals(CheckState.Error, final.single().state)
            assertEquals(CheckIssue.RequestDidNotStop, final.single().issue)
            assertEquals(1, calls.get())
        } finally {
            release.countDown(); job.cancel(); dispatcher.drain(); job.join()
            workers.shutdown(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun delayedDispatcherCannotHideNativeRequestBehindTransportDeadline() = runTest {
        val release = CountDownLatch(1); val entered = CountDownLatch(1)
        val workers = java.util.concurrent.Executors.newSingleThreadExecutor()
        val dispatcher = PausedDispatcher(StandardTestDispatcher(testScheduler))
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return ProbeReply(120, false)
            }
            override fun close() {}
        } }, workers = workers)
        val job = async(dispatcher) { runCatching { transport.measure("fixture", 1) }.exceptionOrNull() }
        try {
            dispatcher.drain()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // Native timeout fires at 4750, but a busy UI resumes the waiter at 4950.
            advanceTimeBy(4950); runCurrent(); dispatcher.drain()
            // The 100 ms grace now crosses the transport's 5000 ms deadline.
            advanceTimeBy(50); runCurrent(); dispatcher.drain()
            assertTrue(job.await() is ProbeRequestDidNotStopException)
        } finally {
            release.countDown(); job.cancel(); dispatcher.drain(); job.join()
            workers.shutdown(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun uncooperativeRequestIsAnErrorAndIsNeverRepeated() = runBlocking {
        val release = CountDownLatch(1); val calls = AtomicInteger()
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply {
                calls.incrementAndGet(); check(release.await(5, TimeUnit.SECONDS)); return ProbeReply(120, false)
            }
            override fun close() {}
        } }, firstTimeoutMs = 50, retryTimeoutMs = 50, requestSlackMs = 20, cancellationGraceMs = 30)
        try {
            val final = ConnectionChecks.run(listOf("fixture"), transport::measure, {}, retryPauseMs = 0)
            assertEquals(CheckState.Error, final.single().state)
            assertEquals(CheckIssue.RequestDidNotStop, final.single().issue)
            assertEquals(1, calls.get())
        } finally { release.countDown() }
    }

    @Test fun queuedRequestNeverStartedIsNotReportedAsServerNoResponse() = runBlocking {
        val pending = mutableListOf<Runnable>(); var factories = 0
        val transport = ProbeTransport(factory = { factories++; error("must not run") },
            workers = java.util.concurrent.Executor { pending.add(it) },
            firstTimeoutMs = 20, retryTimeoutMs = 20, requestSlackMs = 10, cancellationGraceMs = 20)
        val final = ConnectionChecks.run(listOf("fixture"), transport::measure, {}, retryPauseMs = 0)
        assertEquals(CheckState.Error, final.single().state)
        assertEquals(1, final.single().attempt)
        pending.forEach(Runnable::run)
        assertEquals(0, factories)
    }

    @Test fun controlErrorDuringCancellationGraceRemainsAnErrorWithoutPrivateDetails() = runBlocking {
        val release = CountDownLatch(1)
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply {
                check(release.await(5, TimeUnit.SECONDS)); error("private fixture control detail")
            }
            override fun close() { release.countDown() }
        } }, firstTimeoutMs = 50, retryTimeoutMs = 50, requestSlackMs = 20, cancellationGraceMs = 100)
        try {
            val final = ConnectionChecks.run(listOf("fixture"), transport::measure, {}, retryPauseMs = 0)
            assertEquals(CheckState.Error, final.single().state)
            assertFalse(final.toString().contains("private fixture control detail"))
        } finally { release.countDown() }
    }

    @Test fun actualBlockingCallsStayBoundedAcrossCancellationAndNewRuns() = runBlocking {
        val release = CountDownLatch(1); val started = CountDownLatch(8); val manualStarted = CountDownLatch(6)
        val calls = AtomicInteger(); val active = AtomicInteger(); val maximum = AtomicInteger()
        fun transport(priority: ProbePriority = ProbePriority.ManualScan) = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply {
                calls.incrementAndGet()
                val current = active.incrementAndGet(); maximum.accumulateAndGet(current, ::maxOf)
                started.countDown()
                manualStarted.countDown()
                try { check(release.await(10, TimeUnit.SECONDS)); return ProbeReply(120, false) }
                finally { active.decrementAndGet() }
            }
            override fun close() {}
        } }, priority = priority)
        val first = transport(); val second = transport()
        val events = mutableListOf<CheckResult>()
        val job = async { ConnectionChecks.run((0..11).map { "fixture-$it" }, first::measure, events::add) }
        val activeJobs = mutableListOf<Job>()
        try {
            assertTrue(withContext(Dispatchers.IO) { manualStarted.await(5, TimeUnit.SECONDS) })
            assertEquals(6, calls.get())
            repeat(2) { activeJobs.add(launch { transport(ProbePriority.Active).measure("current-$it", 1) }) }
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            job.cancelAndJoin(); activeJobs.forEach { it.cancelAndJoin() }
            val next = ConnectionChecks.run((0..11).map { "next-$it" }, second::measure, {}, totalDeadlineMs = 80)
            assertEquals(8, calls.get()); assertEquals(8, active.get()); assertEquals(8, maximum.get())
            assertTrue(next.all { it.state == CheckState.NotCompleted })
            assertFalse(events.any { it.state == CheckState.Working })
        } finally {
            job.cancel(); activeJobs.forEach { it.cancel() }; release.countDown(); job.join(); activeJobs.forEach { it.join() }
            withTimeout(5000) { while (active.get() != 0) delay(1) }
        }
    }
}
