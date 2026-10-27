package io.nekohasekai.sfa.bg

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchdogCheckGateTest {
    @Test fun startupKeepsOnlyTheLatestSignalWithoutSkippingTheFirstCheck() = runTest {
        val calls = mutableListOf<String>()
        val gate = WatchdogCheckGate(backgroundScope, 1000, { 90_000 }) { calls.add(it) }
        gate.request("old"); gate.request("latest"); runCurrent()
        assertTrue(calls.isEmpty())
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("latest"), calls)
        gate.close()
    }
    @Test fun eventBurstDuringCheckProducesOneCurrentPendingCheck() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val gate = WatchdogCheckGate(backgroundScope, 0, { 90_000 }) {
            calls.add(it)
            if (calls.size == 1) { entered.complete(Unit); release.await() }
        }
        runCurrent(); entered.await()
        repeat(20) { gate.request("signal $it") }; runCurrent()
        assertEquals(1, calls.size)
        release.complete(Unit); runCurrent()
        assertEquals(listOf("цикл", "signal 19"), calls)
        gate.close()
    }
    @Test fun closingDropsPendingSignalsAndCancelsTheRunningCheck() = runTest {
        val calls = mutableListOf<String>(); var cancelled = false
        val gate = WatchdogCheckGate(backgroundScope, 0, { 90_000 }) {
            calls.add(it)
            try { awaitCancellation() } finally { cancelled = true }
        }
        runCurrent(); gate.request("pending"); gate.close(); runCurrent(); advanceTimeBy(100_000)
        assertTrue(cancelled)
        assertEquals(listOf("цикл"), calls)
    }
    @Test fun nextPeriodicCheckUsesNewFailureIntervalAndAnExceptionDoesNotKillTheGate() = runTest {
        var interval = 90_000L; var calls = 0
        val gate = WatchdogCheckGate(backgroundScope, 0, { interval }) { calls++; interval = 15_000; error("fixture") }
        runCurrent(); assertEquals(1, calls)
        advanceTimeBy(14_999); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(2, calls)
        gate.close()
    }
}
