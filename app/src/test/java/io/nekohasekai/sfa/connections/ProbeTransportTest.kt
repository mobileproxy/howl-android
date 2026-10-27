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
        } }, workers = workers, closers = closers)
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
}
