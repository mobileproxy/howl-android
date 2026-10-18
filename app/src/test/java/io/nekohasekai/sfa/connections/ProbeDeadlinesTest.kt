package io.nekohasekai.sfa.connections

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProbeDeadlinesTest {
    @Test fun attemptDeadlineDuringCancellationGraceCannotBecomeNoResponseOrRetry() = runBlocking {
        val release = CountDownLatch(1); val calls = AtomicInteger()
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply {
                calls.incrementAndGet(); check(release.await(5, TimeUnit.SECONDS)); return ProbeReply(120, false)
            }
            override fun close() {}
        } }, firstTimeoutMs = 100, retryTimeoutMs = 100, requestSlackMs = 0, cancellationGraceMs = 100)
        try {
            val final = ConnectionChecks.run(listOf("fixture"), transport::measure, {}, retryPauseMs = 0, attemptDeadlineMs = 150)
            assertEquals(CheckState.Error, final.single().state)
            assertEquals(CheckIssue.RequestDidNotStop, final.single().issue)
            assertEquals(1, calls.get())
        } finally { release.countDown() }
    }

    @Test fun delayedDispatcherCannotHideNativeRequestBehindTransportDeadline() = runBlocking {
        val release = CountDownLatch(1)
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply {
                check(release.await(8, TimeUnit.SECONDS)); return ProbeReply(120, false)
            }
            override fun close() {}
        } })
        val job = async { runCatching { transport.measure("fixture", 1) }.exceptionOrNull() }
        try {
            yield()
            delay(4700)
            // Deliberately emulate a short busy Main event loop: grace then crosses 5s.
            Thread.sleep(250)
            assertTrue(job.await() is ProbeRequestDidNotStopException)
        } finally { release.countDown(); job.cancelAndJoin() }
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
        val release = CountDownLatch(1); val started = CountDownLatch(8)
        val calls = AtomicInteger(); val active = AtomicInteger(); val maximum = AtomicInteger()
        fun transport() = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply {
                calls.incrementAndGet()
                val current = active.incrementAndGet(); maximum.accumulateAndGet(current, ::maxOf)
                started.countDown()
                try { check(release.await(10, TimeUnit.SECONDS)); return ProbeReply(120, false) }
                finally { active.decrementAndGet() }
            }
            override fun close() {}
        } })
        val first = transport(); val second = transport()
        val events = mutableListOf<CheckResult>()
        val job = async { ConnectionChecks.run((0..11).map { "fixture-$it" }, first::measure, events::add) }
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            val next = ConnectionChecks.run((0..11).map { "next-$it" }, second::measure, {}, totalDeadlineMs = 80)
            assertEquals(8, calls.get()); assertEquals(8, active.get()); assertEquals(8, maximum.get())
            assertTrue(next.all { it.state == CheckState.NotCompleted })
            assertFalse(events.any { it.state == CheckState.Working })
        } finally {
            job.cancel(); release.countDown(); job.join()
            withTimeout(5000) { while (active.get() != 0) delay(1) }
        }
    }
}
