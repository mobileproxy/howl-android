package io.nekohasekai.sfa.bg

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WatchdogRecoveryQueueTest {
    @Test fun recoveredMobileBackupStaysRequestedUntilWeLeaveItOrStopVpn() {
        assertTrue(WatchdogNetworkHold.retainCellularBackup(true, false, false))
        assertFalse(WatchdogNetworkHold.retainCellularBackup(true, false, true))
        assertFalse(WatchdogNetworkHold.retainCellularBackup(true, true, false))
        assertFalse(WatchdogNetworkHold.retainCellularBackup(false, false, false))
    }
    @Test fun emptyGroupAdvancesAndFailedConfirmationContinuesWithinSameSearch() = runTest {
        val nodes = (1..13).map(Int::toString)
        val batches = mutableListOf<List<String>>()
        val applied = mutableListOf<String>()
        val outcome = searchWatchdogReserves(nodes, emptySet(), current = { true },
            measure = { group, _ ->
                batches.add(group)
                group.map { ReserveMeasurement(it, if (it.toInt() > 6) 30 else null, true) }
            },
            apply = {
                applied.add(it.tag)
                if (it.tag == "13") WatchdogProbeResult.Reachable else WatchdogProbeResult.NoResponse
            })
        assertEquals(nodes.chunked(6), batches)
        assertEquals(nodes.drop(6), applied)
        assertEquals(ReserveSearchOutcome.Recovered, outcome)
    }

    @Test fun abandonedProbesFollowUntouchedCandidatesAndNeverRetryAnAppliedRoute() = runTest {
        val batches = mutableListOf<List<String>>()
        val outcome = searchWatchdogReserves((1..8).map(Int::toString), emptySet(), current = { true },
            measure = { group, _ ->
                batches.add(group)
                if (batches.size == 1) listOf(ReserveMeasurement("1", 20, true))
                else group.map { ReserveMeasurement(it, null, false) }
            },
            apply = { WatchdogProbeResult.NoResponse })
        assertEquals(listOf((1..6).map(Int::toString), listOf("7", "8"), (2..6).map(Int::toString)), batches)
        assertEquals(ReserveSearchOutcome.Exhausted, outcome)
    }

    @Test fun sameCityGetsOnlyShortHeadStartAndLosesWhenLatencyGapIsTooLarge() = runTest {
        suspend fun chosen(cityDelay: Long, cityLatency: Int): String {
            val preferred = setOf("city")
            val measured = probeReserveBatch(listOf("remote", "city"), 9400, preferred = preferred) {
                if (it == "city") { delay(cityDelay); cityLatency } else 100
            }
            return rankReserves(measured, preferred).first().tag
        }
        assertEquals("city", chosen(749, 350))
        assertEquals("remote", chosen(749, 351))
        assertEquals("remote", chosen(751, 101))
    }

    @Test fun workFromSupersededContextCannotApplyCandidate() = runTest {
        var current = true
        val outcome = searchWatchdogReserves(listOf("a"), emptySet(), current = { current },
            measure = { _, _ -> current = false; listOf(ReserveMeasurement("a", 20, true)) },
            apply = { error("A stale measurement authorized a switch") })
        assertEquals(ReserveSearchOutcome.Superseded, outcome)
    }

    @Test fun sharedDeadlineRetainsProgressAndCancelsUnfinishedGroup() = runTest {
        val nodes = (1..8).map(Int::toString)
        val consumed = mutableListOf<String>()
        var cancelled = false
        val outcome = searchWatchdogReserves(nodes, emptySet(), current = { true },
            measure = { group, _ ->
                if (group.first() == "7") try { awaitCancellation() } finally { cancelled = true }
                group.map { ReserveMeasurement(it, null, true) }
            },
            apply = { error("No candidate answered") },
            consumed = { consumed.add(it) }, budgetMs = 500)
        assertEquals(ReserveSearchOutcome.Deadline, outcome)
        assertTrue(cancelled)
        assertEquals(nodes.take(6), consumed)
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
