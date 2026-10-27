package io.nekohasekai.sfa.health

import org.junit.Assert.*
import org.junit.Test

class ServiceRecoveryLedgerTest {
    private val id = "12345678-1234-4234-8234-123456789abc"
    private val marker = ServiceSessionMarker(id, 42, "how.vpn.howl", 1_000_000, 100_000)
    private val exit = ProcessExitRecord(42, "how.vpn.howl", 1_010_000, ExitReason.LOW_MEMORY)
    private fun assess(previous: ServiceSessionMarker? = marker, records: List<ProcessExitRecord> = listOf(exit),
        pid: Int = 43, process: String = "how.vpn.howl", wall: Long = 1_020_000,
        elapsed: Long = 120_000, supported: Boolean = true) = ServiceRecoveryLedger.assessPrevious(
        previous, records, pid, process, wall, elapsed, supported)

    @Test fun markerRoundTripsSeparateSessionAndStopKind() {
        val encoded = ServiceSessionMarkerCodec.encode(marker.copy(plannedStop = PlannedStop.RELOAD,
            startKind = SessionStartKind.CONFIG_RELOAD))
        assertNotNull(encoded)
        assertEquals(marker.copy(plannedStop = PlannedStop.RELOAD, startKind = SessionStartKind.CONFIG_RELOAD),
            ServiceSessionMarkerCodec.decode(encoded))
    }

    @Test fun generatedSessionIdsAreDifferentCanonicalUuids() {
        val first = ServiceSessionMarker.create(42, "how.vpn.howl", 1_000_000, 100_000)
        val second = ServiceSessionMarker.create(42, "how.vpn.howl", 1_000_000, 100_000)
        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals(first.sessionId, java.util.UUID.fromString(first.sessionId).toString())
        assertEquals(4, java.util.UUID.fromString(first.sessionId).version())
    }

    @Test fun codecRejectsNoncanonicalIdAndBadProcessIdentity() {
        assertNull(ServiceSessionMarkerCodec.encode(marker.copy(sessionId = "1-1-1-1-1")))
        assertNull(ServiceSessionMarkerCodec.encode(marker.copy(sessionId = id.uppercase())))
        assertNull(ServiceSessionMarkerCodec.encode(marker.copy(pid = 0)))
        assertNull(ServiceSessionMarkerCodec.encode(marker.copy(processName = "bad|process")))
        assertNull(ServiceSessionMarkerCodec.encode(marker.copy(processName = "x".repeat(161))))
        assertNull(ServiceSessionMarkerCodec.encode(marker.copy(startedElapsedMs = -1)))
    }

    @Test fun codecRejectsCorruptUnsupportedAndOversizedMarkersWithoutEcho() {
        val secret = "https://private.invalid/?token=secret-password"
        assertNull(ServiceSessionMarkerCodec.decode(secret))
        assertNull(ServiceSessionMarkerCodec.decode("x".repeat(1025)))
        val initial = ServiceSessionMarkerCodec.encode(marker)
        assertNotNull(initial)
        val encoded = initial!!
        assertNull(ServiceSessionMarkerCodec.decode("2" + encoded.substring(1)))
        assertNull(ServiceSessionMarkerCodec.decode(encoded + "|" + secret))
        assertNull(ServiceSessionMarkerCodec.decode(encoded.replace("ACTIVE", secret)))
        val summary = assess(ServiceSessionMarkerCodec.decode(secret)).toString()
        assertFalse(summary.contains("secret"))
        assertFalse(marker.copy(processName = "private_secret_name").toString().contains("private_secret_name"))
        assertFalse(exit.copy(processName = "private_secret_name").toString().contains("private_secret_name"))
    }

    @Test fun matchesOnlyRelevantPreviousProcessRecord() {
        assertEquals(ExitAssessmentKind.MATCHED, assess().kind)
        assertEquals(ExitReason.LOW_MEMORY, assess().reason)
        assertEquals(ExitReason.CRASH, assess(records = listOf(exit, exit.copy(timestampWallMs = 1_015_000,
            reason = ExitReason.CRASH))).reason)
    }

    @Test fun wrongPidDoesNotExplainServiceAbsence() {
        assertEquals(ExitAssessmentKind.UNKNOWN, assess(records = listOf(exit.copy(pid = 41))).kind)
    }

    @Test fun wrongProcessDoesNotExplainServiceAbsence() {
        assertEquals(ExitAssessmentKind.UNKNOWN, assess(records = listOf(exit.copy(processName = "another.process"))).kind)
        assertEquals(ExitAssessmentKind.UNKNOWN, assess(process = "another.process").kind)
    }

    @Test fun conflictingNewestReasonsAreAmbiguousAndStayUnknown() {
        assertEquals(ExitAssessmentKind.UNKNOWN, assess(records = listOf(exit,
            exit.copy(reason = ExitReason.CRASH))).kind)
        assertEquals(ExitReason.UNKNOWN, assess(records = listOf(exit.copy(reason = ExitReason.UNKNOWN))).reason)
    }

    @Test fun recordBeforeSessionStartIsStaleEvenWhenPidWasReused() {
        assertEquals(ExitAssessmentKind.UNKNOWN, assess(records = listOf(exit.copy(timestampWallMs = 999_999))).kind)
    }

    @Test fun futureRecordIsRejected() {
        assertEquals(ExitAssessmentKind.UNKNOWN, assess(records = listOf(exit.copy(timestampWallMs = 1_020_001))).kind)
    }

    @Test fun oldExitDoesNotExplainFreshRestartAfterLongAbsence() {
        assertEquals(ExitAssessmentKind.UNKNOWN, assess(wall = 100_000_000, elapsed = 99_100_000).kind)
    }

    @Test fun missingHistoryAndSameProcessAbsenceStayUnknown() {
        assertEquals(ExitAssessmentKind.UNKNOWN, assess(records = emptyList()).kind)
        assertEquals(ExitAssessmentKind.UNKNOWN, assess(pid = 42).kind)
        assertEquals(ExitReason.UNKNOWN, assess(pid = 42).reason)
        assertEquals(ExitAssessmentKind.UNKNOWN, assess(previous = null).kind)
    }

    @Test fun olderApiIsUnsupportedAndDoesNotGuessSystemKill() {
        assertEquals(ExitAssessmentKind.UNSUPPORTED, assess(supported = false).kind)
        assertEquals(ExitReason.UNKNOWN, assess(supported = false).reason)
    }

    @Test fun reloadAndExplicitStopsAreDistinctPlannedOutcomesWithoutDeath() {
        for (stop in listOf(PlannedStop.USER, PlannedStop.REVOKE, PlannedStop.FAILURE, PlannedStop.RELOAD)) {
            val result = assess(previous = marker.copy(plannedStop = stop))
            assertEquals(ExitAssessmentKind.PLANNED, result.kind)
            assertEquals(stop, result.plannedStop)
            assertEquals(ExitReason.UNKNOWN, result.reason)
        }
    }

    @Test fun bootOffsetMismatchAndClockReversalCannotMatchHistory() {
        assertEquals(ExitAssessmentKind.UNKNOWN, assess(elapsed = 10_000).kind)
        assertEquals(ExitAssessmentKind.UNKNOWN, assess(wall = 1_030_000).kind)
        assertEquals(ExitAssessmentKind.UNKNOWN, assess(wall = 990_000).kind)
        assertEquals(ExitAssessmentKind.MATCHED, assess(wall = 1_020_500).kind)
    }

    @Test fun negativeAndOverflowProneClockInputsAreRejected() {
        assertEquals(ExitAssessmentKind.UNKNOWN, assess(wall = -1).kind)
        assertEquals(ExitAssessmentKind.UNKNOWN, assess(elapsed = -1).kind)
        assertEquals(ExitAssessmentKind.UNKNOWN, assess(previous = marker.copy(startedWallMs = Long.MAX_VALUE)).kind)
    }

    @Test fun lookupNeverSearchesPastBoundedRecentRecords() {
        val records = List(32) { exit.copy(pid = 999) } + exit
        assertEquals(ExitAssessmentKind.UNKNOWN, assess(records = records).kind)
    }

    @Test fun coreRunningCompletesOnlyServiceRestoration() {
        val ledger = ServiceRecoveryLedger(id, SessionStartKind.GUARD_RESTART)
        val service = ledger.beginServiceRestoration(100)!!
        val network = ledger.beginNetworkRestoration(120)!!
        assertTrue(ledger.serviceRunning(service, 300))
        val state = ledger.snapshot()
        assertEquals(1L, state.service.successes)
        assertEquals(200L, state.service.lastDurationMs)
        assertEquals(0L, state.network.successes)
        assertTrue(state.network.pending)
        assertFalse(ledger.serviceRunning(service, 350))
        assertTrue(ledger.networkConnected(network, 360, 400))
        assertEquals(280L, ledger.snapshot().network.lastDurationMs)
    }

    @Test fun repeatedBeginCoalescesOneAttempt() {
        val ledger = ServiceRecoveryLedger(id)
        val first = ledger.beginNetworkRestoration(100)
        assertEquals(first, ledger.beginNetworkRestoration(200))
        assertEquals(1L, ledger.snapshot().network.attempts)
    }

    @Test fun staleRequestsAndForeignTicketsCannotRestoreNetwork() {
        val ledger = ServiceRecoveryLedger(id)
        val ticket = ledger.beginNetworkRestoration(200)!!
        assertFalse(ledger.networkConnected(ticket, 199, 300))
        assertFalse(ledger.networkConnected(ticket, 301, 300))
        assertFalse(ledger.networkConnected(ticket.copy(sessionId = "22345678-1234-4234-8234-123456789abc"), 250, 300))
        assertFalse(ledger.serviceRunning(ticket, 300))
        assertEquals(0L, ledger.snapshot().network.successes)
    }

    @Test fun lateSuccessfulRequestDoesNotCountAsFreshNetworkRestoration() {
        val ledger = ServiceRecoveryLedger(id)
        val ticket = ledger.beginNetworkRestoration(200)!!
        assertFalse(ledger.networkConnected(ticket, 200, 120_201))
        assertTrue(ledger.networkConnected(ticket, 120_200, 120_300))
        assertEquals(1L, ledger.snapshot().network.successes)
    }

    @Test fun explicitStopCancelsRestorationAndPreventsLateRevival() {
        val ledger = ServiceRecoveryLedger(id)
        val service = ledger.beginServiceRestoration(100)!!
        val network = ledger.beginNetworkRestoration(100)!!
        assertTrue(ledger.stop(PlannedStop.USER, 200))
        assertFalse(ledger.networkConnected(network, 250, 300))
        assertFalse(ledger.serviceRunning(service, 300))
        assertNull(ledger.beginNetworkRestoration(400))
        assertNull(ledger.beginServiceRestoration(400))
        assertEquals(1L, ledger.snapshot().service.cancelled)
        assertEquals(1L, ledger.snapshot().network.cancelled)
        assertEquals(PlannedStop.USER, ledger.snapshot().plannedStop)
        assertFalse(ledger.stop(PlannedStop.REVOKE, 500))
        assertEquals(PlannedStop.USER, ledger.snapshot().plannedStop)
    }

    @Test fun monotonicDurationsIgnoreWallTimeAndRejectElapsedReversal() {
        val ledger = ServiceRecoveryLedger(id)
        val first = ledger.beginNetworkRestoration(100)!!
        assertFalse(ledger.networkConnected(first, 110, 90))
        assertTrue(ledger.networkConnected(first, 110, 200))
        val second = ledger.beginNetworkRestoration(300)!!
        assertFalse(ledger.networkConnected(first, 310, 400))
        assertTrue(ledger.networkConnected(second, 310, 450))
        val metrics = ledger.snapshot().network
        assertEquals(2L, metrics.attempts)
        assertEquals(2L, metrics.successes)
        assertEquals(150L, metrics.lastDurationMs)
        assertEquals(250L, metrics.totalDurationMs)
    }

    @Test fun restorationDurationIsNotWholePriorProcessLifetime() {
        val ledger = ServiceRecoveryLedger(id)
        assertEquals(ExitAssessmentKind.MATCHED, assess().kind)
        assertEquals(0L, ledger.snapshot().service.totalDurationMs)
        val ticket = ledger.beginServiceRestoration(120_000)!!
        assertTrue(ledger.serviceRunning(ticket, 120_250))
        assertEquals(250L, ledger.snapshot().service.lastDurationMs)
    }

    @Test fun durationIsBoundedAndActiveIsNotAStopReason() {
        val ledger = ServiceRecoveryLedger(id)
        assertFalse(ledger.stop(PlannedStop.ACTIVE, 0))
        val ticket = ledger.beginServiceRestoration(0)!!
        assertTrue(ledger.serviceRunning(ticket, Long.MAX_VALUE))
        assertEquals(604_800_000L, ledger.snapshot().service.lastDurationMs)
    }
}
