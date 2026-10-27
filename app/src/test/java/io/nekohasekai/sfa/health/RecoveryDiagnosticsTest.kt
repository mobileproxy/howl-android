package io.nekohasekai.sfa.health

import org.junit.Assert.*
import org.junit.Test

class RecoveryDiagnosticsTest {
    private val id = "12345678-1234-4234-8234-123456789abc"
    private fun diagnostics() = RecoveryDiagnostics(id, minEmissionIntervalMs = 1_000, maxPendingSummaries = 3)
    private fun only(summaries: List<RecoveryDiagnosticSummary>): RecoveryDiagnosticSummary {
        assertEquals(1, summaries.size)
        return summaries[0]
    }

    @Test fun repeatedFailureAggregatesFirstLastCountAttemptsAndResult() {
        val diagnostics = diagnostics()
        val first = diagnostics.transition(1, RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.FAILED, 100, attempted = true)
        assertEquals(1, first.size)
        assertTrue(diagnostics.transition(1, RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.FAILED, 200, attempted = true).isEmpty())
        assertTrue(diagnostics.transition(1, RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.UNAVAILABLE, 300).isEmpty())
        val summary = only(diagnostics.drain(1_100))
        assertEquals(100L, summary.firstElapsedMs)
        assertEquals(300L, summary.lastElapsedMs)
        assertEquals(3L, summary.count)
        assertEquals(2L, summary.attempts)
        assertEquals(RecoveryResult.UNAVAILABLE, summary.result)
    }

    @Test fun unchangedSnapshotDoesNotEmitRepeatedDuplicates() {
        val diagnostics = diagnostics()
        diagnostics.transition(1, RecoveryCause.NO_PHYSICAL_NETWORK, RecoveryResult.FAILED, 0)
        assertTrue(diagnostics.drain(1_000).isEmpty())
        assertTrue(diagnostics.drain(2_000).isEmpty())
        assertTrue(diagnostics.transition(1, RecoveryCause.NONE, RecoveryResult.RECOVERED, 3_000).isNotEmpty())
        assertTrue(diagnostics.transition(1, RecoveryCause.NONE, RecoveryResult.RECOVERED, 4_000).isEmpty())
    }

    @Test fun changingCauseFlushesOldFailureBeforeNewOne() {
        val diagnostics = diagnostics()
        diagnostics.transition(1, RecoveryCause.NO_PHYSICAL_NETWORK, RecoveryResult.FAILED, 100)
        diagnostics.transition(1, RecoveryCause.NO_PHYSICAL_NETWORK, RecoveryResult.FAILED, 200)
        assertTrue(diagnostics.transition(1, RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.FAILED, 300).isEmpty())
        val prior = only(diagnostics.drain(1_100))
        assertEquals(RecoveryCause.NO_PHYSICAL_NETWORK, prior.cause)
        assertEquals(2L, prior.count)
        val current = only(diagnostics.drain(2_100))
        assertEquals(RecoveryCause.SELECTED_REQUESTS_FAILED, current.cause)
        assertEquals(1L, current.count)
    }

    @Test fun newScopeDoesNotMergeOldProfileOrNetworkFailure() {
        val diagnostics = diagnostics()
        diagnostics.transition(1, RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.FAILED, 0)
        diagnostics.transition(2, RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.FAILED, 100)
        val summary = only(diagnostics.drain(1_000))
        assertEquals(1L, summary.count)
        assertEquals(100L, summary.firstElapsedMs)
        assertTrue(diagnostics.transition(1, RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.FAILED, 1_100).isEmpty())
        assertTrue(diagnostics.drain(2_000).isEmpty())
    }

    @Test fun recoveredOnlyClosesCurrentScopeAndIncludesFinalOutcome() {
        val diagnostics = diagnostics()
        diagnostics.transition(2, RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.FAILED, 0, attempted = true)
        assertTrue(diagnostics.transition(1, RecoveryCause.NONE, RecoveryResult.RECOVERED, 500).isEmpty())
        val summary = only(diagnostics.transition(2, RecoveryCause.NONE, RecoveryResult.RECOVERED, 1_000))
        assertEquals(RecoveryResult.RECOVERED, summary.result)
        assertEquals(RecoveryCause.SELECTED_REQUESTS_FAILED, summary.cause)
        assertEquals(1_000L, summary.lastElapsedMs)
        assertEquals(1L, summary.count)
        assertTrue(diagnostics.drain(2_000).isEmpty())
    }

    @Test fun stopFlushesOnceCancelsCurrentAndRejectsLateEvents() {
        val diagnostics = diagnostics()
        diagnostics.transition(1, RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.FAILED, 100, attempted = true)
        val terminal = diagnostics.stop(200)
        assertEquals(RecoveryResult.CANCELLED, only(terminal).result)
        assertEquals(200L, only(terminal).lastElapsedMs)
        assertTrue(diagnostics.stop(300).isEmpty())
        assertTrue(diagnostics.transition(1, RecoveryCause.NONE, RecoveryResult.RECOVERED, 500).isEmpty())
        assertTrue(diagnostics.drain(1_000).isEmpty())
    }

    @Test fun rapidChangesHaveBoundedBufferAndBoundedNormalEmission() {
        val diagnostics = diagnostics()
        var emitted = 0
        for (index in 0..50) {
            emitted += diagnostics.transition(index.toLong(), RecoveryCause.SELECTED_REQUESTS_FAILED,
                RecoveryResult.FAILED, index.toLong()).size
        }
        assertEquals(1, emitted)
        val final = diagnostics.stop(100)
        assertTrue(final.size <= 3)
        assertTrue(final.sumOf { it.droppedSummaries } > 0)
        assertTrue(diagnostics.stop(200).isEmpty())
    }

    @Test fun negativeOrReversedElapsedTimeDoesNotPoisonAggregation() {
        val diagnostics = diagnostics()
        assertTrue(diagnostics.transition(1, RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.FAILED, -1).isEmpty())
        diagnostics.transition(1, RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.FAILED, 100)
        assertTrue(diagnostics.transition(1, RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.FAILED, 99).isEmpty())
        val terminal = only(diagnostics.stop(200))
        assertEquals(1L, terminal.count)
        assertEquals(100L, terminal.firstElapsedMs)
    }

    @Test fun typedSummaryCannotCarrySecretMarkerNamesOrRawFields() {
        val diagnostics = diagnostics()
        diagnostics.transition(1, RecoveryCause.PROCESS_EXIT, RecoveryResult.FAILED, 100)
        val fields = only(diagnostics.stop(200)).allowlistedFields()
        assertEquals(setOf("cause", "result", "firstElapsedMs", "lastElapsedMs", "count", "attempts", "droppedSummaries"), fields.keys)
        assertEquals("PROCESS_EXIT", fields["cause"])
        assertTrue(fields.values.all { it.matches(Regex("[A-Z_]+|[0-9]+")) })
        val secret = "token=private://password@host/node"
        assertNull(ServiceSessionMarkerCodec.decode(secret))
        assertFalse(fields.toString().contains("token"))
        assertFalse(fields.toString().contains("host"))
    }

    @Test fun noneOrRecoveredDoesNotCreateFailureAndCancelledIsTerminalResult() {
        val diagnostics = diagnostics()
        assertTrue(diagnostics.transition(1, RecoveryCause.NONE, RecoveryResult.FAILED, 0).isEmpty())
        assertTrue(diagnostics.transition(1, RecoveryCause.NONE, RecoveryResult.RECOVERED, 100).isEmpty())
        diagnostics.transition(1, RecoveryCause.PROBE_UNAVAILABLE, RecoveryResult.UNAVAILABLE, 200)
        assertEquals(RecoveryResult.CANCELLED,
            only(diagnostics.transition(1, RecoveryCause.NONE, RecoveryResult.CANCELLED, 1_200)).result)
    }
}
