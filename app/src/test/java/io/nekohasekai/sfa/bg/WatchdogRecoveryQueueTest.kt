package io.nekohasekai.sfa.bg

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WatchdogRecoveryQueueTest {
    @Test fun recoveredMobileBackupStaysRequestedUntilWeLeaveItOrStopVpn() {
        assertTrue(WatchdogNetworkHold.retainCellularBackup(true, false, false))
        assertFalse(WatchdogNetworkHold.retainCellularBackup(true, false, true))
        assertFalse(WatchdogNetworkHold.retainCellularBackup(true, true, false))
        assertFalse(WatchdogNetworkHold.retainCellularBackup(false, false, false))
    }
    @Test fun everyReserveIsVisitedBeforeAnyRetryEvenWhenAllResultsAreUnknown() {
        val q = WatchdogRecoveryQueue(); val nodes = (1..17).map { "node$it" }
        assertEquals(nodes.take(6), q.next(nodes, 6))
        assertEquals(nodes.reversed().take(6), q.next(nodes.reversed(), 6))
    }
    @Test fun fullPassAndRetryAreSeparateAndResetStartsFresh() {
        val q = WatchdogRecoveryQueue(); val nodes = (1..17).map { "node$it" }
        val first = q.next(nodes, 6) + q.next(nodes, 6) + q.next(nodes, 6)
        assertEquals(nodes, first); assertEquals(0, q.pass)
        assertEquals(nodes.take(6), q.next(nodes, 6)); assertEquals(1, q.pass)
        q.reset(); assertEquals(0, q.pass); assertEquals(nodes.take(6), q.next(nodes, 6))
    }
    @Test fun reorderedPoolDoesNotRevisitAndNewNodesJoinTheCurrentPass() {
        val q = WatchdogRecoveryQueue()
        assertEquals(listOf("a", "b"), q.next(listOf("a", "b", "c"), 2))
        assertEquals(listOf("d", "c"), q.next(listOf("b", "d", "c", "a"), 2))
        assertEquals(emptyList<String>(), q.next(emptyList(), 2))
    }
    @Test fun ownNetworkCallbacksCannotBounceWithinHoldButLossBypassesIt() {
        val h = WatchdogNetworkHold()
        assertTrue(h.allowed(100, true)); h.changed(100)
        assertFalse(h.allowed(11_100, true)); assertFalse(h.allowed(60_099, true))
        assertTrue(h.allowed(60_100, true)); assertTrue(h.allowed(101, false))
        h.reset(); assertTrue(h.allowed(102, true))
    }
    @Test fun successfulReserveDoesNotWaitForHungNeighbour() = runBlocking {
        var cancelled = false
        val result = withTimeout(1000) {
            probeReserveBatch(listOf("hang", "works"), 5000, 20) {
                if (it == "hang") try { awaitCancellation() } finally { cancelled = true } else 42
            }
        }
        assertEquals(listOf(ReserveMeasurement("works", 42, true)), result)
        assertTrue(cancelled)
    }
    @Test fun timeoutAndExceptionRemainUnknownAndCompletedFailureIsDistinct() = runBlocking {
        val result = probeReserveBatch(listOf("timeout", "exception", "failed"), 30) {
            when (it) { "timeout" -> { delay(5000); 10 }; "exception" -> error("private"); else -> null }
        }.associateBy { it.tag }
        assertFalse(result.getValue("timeout").completed)
        assertFalse(result.getValue("exception").completed)
        assertTrue(result.getValue("failed").completed)
    }
    @Test fun parentCancellationStopsBatchWithoutReturningSelectionEvidence() = runBlocking {
        var returned = false
        val job = launch { probeReserveBatch(listOf("a", "b"), 5000) { awaitCancellation() }; returned = true }
        yield(); job.cancelAndJoin(); assertFalse(returned)
    }
}

