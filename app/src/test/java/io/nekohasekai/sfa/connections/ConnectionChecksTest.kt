package io.nekohasekai.sfa.connections

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionChecksTest {
    @Test fun successIncludesFreshTimeAndZeroIsValid() = runBlocking {
        val events = mutableListOf<CheckResult>()
        val final = ConnectionChecks.run(listOf("zero", "fast", "zero"), { tag, _ -> if (tag == "zero") 0 else 180 }, events::add, now = { 123 })
        assertEquals(2, final.size)
        assertTrue(final.all { it.state == CheckState.Working && it.checkedAt == 123L && it.attempt == 1 })
        assertEquals(0, final.single { it.tag == "zero" }.delayMs)
        assertEquals(2, events.count { it.finished })
    }
    @Test fun retriesOnlyNoResponseAndPreservesHonestOutcomes() = runBlocking {
        val counts = mutableMapOf<String, Int>()
        val events = mutableListOf<CheckResult>()
        val final = ConnectionChecks.run(listOf("ok", "retry", "silent", "error"), { tag, attempt ->
            counts[tag] = (counts[tag] ?: 0) + 1
            when (tag) { "ok" -> 100; "retry" -> if (attempt == 2) 200 else null
                "error" -> error("fixture secret must never reach state"); else -> null }
        }, events::add, retryPauseMs = 0)
        assertEquals(mapOf("ok" to 1, "retry" to 2, "silent" to 2, "error" to 1), counts)
        assertEquals(CheckState.Working, final.single { it.tag == "retry" }.state)
        assertEquals(CheckState.NoResponse, final.single { it.tag == "silent" }.state)
        assertEquals(CheckState.Error, final.single { it.tag == "error" }.state)
        assertTrue(events.any { it.state == CheckState.RetryWaiting })
        assertTrue(events.any { it.state == CheckState.Retrying })
        assertFalse(events.joinToString().contains("fixture secret"))
    }
    @Test fun limitsPoolToEightAndDeliversResultsIncrementally() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var active = 0; var maximum = 0; var entered = 0
        val events = mutableListOf<CheckResult>()
        val job = async { ConnectionChecks.run((1..12).map(Int::toString), { _, _ ->
            active++; maximum = maxOf(maximum, active); entered++
            if (entered == 8) started.complete(Unit)
            try { release.await(); 80 } finally { active-- }
        }, events::add) }
        withTimeout(5000) { started.await() }
        assertEquals(8, entered); assertFalse(job.isCompleted)
        release.complete(Unit); assertEquals(12, job.await().size)
        assertEquals(8, maximum); assertEquals(12, entered); assertEquals(0, active)
        assertEquals(12, events.count { it.state == CheckState.Working })
    }
    @Test fun retryWaitsForRemainingFirstAttempts() = runBlocking {
        val release = CompletableDeferred<Unit>(); val waiting = CompletableDeferred<Unit>()
        val events = mutableListOf<CheckResult>()
        val job = async { ConnectionChecks.run(listOf("slow", "retry"), { tag, attempt ->
            if (tag == "slow") { release.await(); 100 } else if (attempt == 2) 200 else null
        }, { events.add(it); if (it.state == CheckState.RetryWaiting) waiting.complete(Unit) }, retryPauseMs = 0) }
        withTimeout(5000) { waiting.await() }
        assertFalse(job.isCompleted)
        assertFalse(events.any { it.state == CheckState.Retrying })
        release.complete(Unit); assertEquals(2, job.await().size)
    }
    @Test fun cancelActiveAndQueuedWithoutCallingThemUnavailable() = runBlocking {
        var entered = 0; val started = CompletableDeferred<Unit>(); val events = mutableListOf<CheckResult>()
        val job = launch { ConnectionChecks.run((1..10).map(Int::toString), { _, _ ->
            entered++; if (entered == 8) started.complete(Unit); awaitCancellation()
        }, events::add) }
        withTimeout(5000) { started.await() }; job.cancelAndJoin()
        assertEquals(8, entered)
        assertEquals(10, events.count { it.state == CheckState.Cancelled })
        assertFalse(events.any { it.state == CheckState.NoResponse })
    }
    @Test fun cancelDuringPauseDoesNotStartSecondAttempt() = runBlocking {
        val waiting = CompletableDeferred<Unit>(); val events = mutableListOf<CheckResult>()
        val job = launch { ConnectionChecks.run(listOf("node"), { _, _ -> null }, {
            events.add(it); if (it.state == CheckState.RetryWaiting) waiting.complete(Unit)
        }, retryPauseMs = 60000) }
        withTimeout(5000) { waiting.await() }; job.cancelAndJoin()
        assertFalse(events.any { it.state == CheckState.Retrying })
    }
    @Test fun attemptDeadlineProducesTwoAttemptsNotAnApiError() = runBlocking {
        val final = ConnectionChecks.run(listOf("timeout"), { _, _ -> awaitCancellation() }, {}, retryPauseMs = 0, attemptDeadlineMs = 20)
        assertEquals(CheckState.NoResponse, final.single().state)
        assertEquals(2, final.single().attempt)
    }
    @Test fun invalidDelayIsAnError() = runBlocking {
        val final = ConnectionChecks.run(listOf("node"), { _, _ -> -1 }, {})
        assertEquals(CheckState.Error, final.single().state)
    }
    @Test fun stopAndRetryPreserveCompletedTimeAndRejectOldCallbacks() {
        val session = ConnectionCheckSession(); val generation = session.begin(listOf("a", "b", "c"))
        session.apply(generation, CheckResult("a", CheckState.Working, 180, 1, 123))
        session.apply(generation, CheckResult("b", CheckState.NoResponse, attempt = 2))
        assertEquals(2, session.snapshot.completed); assertEquals(1, session.snapshot.working)
        session.cancel()
        assertEquals(CheckState.Cancelled, session.snapshot.results.getValue("c").state)
        assertFalse(session.apply(generation, CheckResult("c", CheckState.Working, 1)))
        val next = session.begin(session.snapshot.unresolved)
        assertEquals(123L, session.snapshot.results.getValue("a").checkedAt)
        assertEquals(2, session.snapshot.total); assertEquals(0, session.snapshot.completed)
        assertFalse(session.apply(next, CheckResult("a", CheckState.Error)))
        session.apply(next, CheckResult("b", CheckState.Retrying))
        assertEquals(1, session.snapshot.repeating)
        session.clear(); assertTrue(session.snapshot.results.isEmpty())
        assertFalse(session.apply(next, CheckResult("b", CheckState.Working, 1)))
    }
    @Test fun terminalResultsCannotBeOverwritten() {
        val session = ConnectionCheckSession(); val generation = session.begin(listOf("a"))
        assertTrue(session.apply(generation, CheckResult("a", CheckState.Working, 10)))
        assertFalse(session.apply(generation, CheckResult("a", CheckState.Checking)))
        assertFalse(session.apply(generation, CheckResult("foreign", CheckState.Error)))
    }
    @Test fun onlyIndividualVpnTargetsAreCheckable() {
        assertTrue(CheckTarget("north", "VLESS").checkable)
        assertTrue(CheckTarget("south", "wireguard").checkable)
        for (type in listOf("selector", "urltest", "direct", "reject", "block", "dns")) assertFalse(CheckTarget("node", type).checkable)
        assertFalse(CheckTarget("auto", "vless").checkable)
        assertEquals("North", CheckTarget("North · Hysteria2", "hysteria2").location)
        assertEquals("Custom", CheckTarget("Custom", "vmess").location)
    }
    @Test fun emptyListMakesNoRequests() = runBlocking {
        val result = ConnectionChecks.run(emptyList(), { _, _ -> error("must not call") }, { error("must not report") })
        assertTrue(result.isEmpty())
    }
}
