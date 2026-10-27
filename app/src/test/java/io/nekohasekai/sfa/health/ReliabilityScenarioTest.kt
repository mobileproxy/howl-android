package io.nekohasekai.sfa.health

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Deterministic contract traces across the real pure models. Direct events below are
 * not an Android adapter/runtime substitute and cannot prove actual VPN/device wiring.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReliabilityScenarioTest {
    private val sessionId = "12345678-1234-4234-8234-123456789abc"
    private val context = HealthContext(Any(), 1, 1, "fixture-selector", "fixture-preferred", Any(), 0)
    private val selectedSuccess = HealthEvidence(physical = ProbeFinding.Responded, selected = ProbeFinding.Responded)

    /** Implements only the actual storage boundary; none of the policies are replaced. */
    private class MemoryStorage : AtomicPreferenceStorage {
        var value: String? = null
        override fun read() = value
        override fun writeAtomically(value: String) { this.value = value }
    }

    private fun remember(preferences: ProfileSelectionPreferences, target: String = "fixture-preferred"): SelectionPreference {
        val intent = preferences.beginManual(1, "fixture-selector", target)!!
        assertEquals(PreferenceWriteResult.Stored, preferences.acknowledgeManual(intent, acknowledged = true) { true })
        return preferences.read(1, "fixture-selector")!!
    }

    private fun returnContext(preference: SelectionPreference, health: HealthContext = context) = PreferredReturnContext(
        health.owner, sessionId, health.profile, preference.selector, health.generation, health.network,
        health.manualRevision, preference.revision, preference.target, eligiblePreferred = true)

    private fun successfulReturnSample(at: Long) = PreferredReturnObservation(PreferredReturnFinding.Success,
        PreferredReturnFinding.Success, at, at, manualIntentCurrent = true)

    @Test fun coldStartNeedsFreshSelectedResponseAfterCoreStarted() = runTest {
        val monitor = HealthMonitor(backgroundScope) { testScheduler.currentTime }
        val ledger = ServiceRecoveryLedger(sessionId)
        val diagnostics = RecoveryDiagnostics(sessionId, minEmissionIntervalMs = 1)
        val service = ledger.beginServiceRestoration(0)!!
        val network = ledger.beginNetworkRestoration(0)!!
        val initial = monitor.begin(context)
        assertEquals(HealthState.Checking, monitor.state.value.assessment.state)
        advanceTimeBy(25)
        assertTrue(ledger.serviceRunning(service, testScheduler.currentTime))
        assertTrue(monitor.observe(initial, HealthEvidence(oldTraffic = true)))
        assertEquals(HealthState.Unverified, monitor.view().assessment.state)
        assertEquals(0L, ledger.snapshot().network.successes)
        diagnostics.transition(1, RecoveryCause.PROBE_UNAVAILABLE, RecoveryResult.UNAVAILABLE, testScheduler.currentTime, attempted = true)

        val fresh = monitor.begin(context)
        advanceTimeBy(25)
        assertTrue(monitor.observe(fresh, selectedSuccess))
        assertTrue(ledger.networkConnected(network, fresh.startedAt, testScheduler.currentTime))
        val recovered = diagnostics.transition(1, RecoveryCause.NONE, RecoveryResult.RECOVERED, testScheduler.currentTime).single()
        assertEquals(HealthState.Available, monitor.state.value.assessment.state)
        assertEquals(HealthScope.SelectedExitResponse, monitor.view().assessment.scope)
        assertFalse(monitor.view().assessment.httpStatusKnown)
        assertEquals(25L, ledger.snapshot().service.lastDurationMs)
        assertEquals(50L, ledger.snapshot().network.lastDurationMs)
        assertEquals(RecoveryResult.RECOVERED, recovered.result)
        assertEquals(25L, recovered.firstElapsedMs)
        assertEquals(50L, recovered.lastElapsedMs)
        monitor.stop()
    }

    @Test fun oldFlowBytesArePartialUntilANewSelectedRequestCompletesNetworkRecovery() = runTest {
        val monitor = HealthMonitor(backgroundScope) { testScheduler.currentTime }
        val ledger = ServiceRecoveryLedger(sessionId)
        val diagnostics = RecoveryDiagnostics(sessionId, minEmissionIntervalMs = 1)
        val network = ledger.beginNetworkRestoration(0)!!
        val failing = monitor.begin(context)
        assertTrue(monitor.observe(failing, HealthEvidence(physical = ProbeFinding.Responded,
            selected = ProbeFinding.Failed, oldTraffic = true, tls = ProbeFinding.Responded)))
        diagnostics.transition(1, RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.FAILED, 0, attempted = true)
        assertEquals(HealthState.Partial, monitor.view().assessment.state)
        assertEquals(HealthCause.SelectedRequestsFailed, monitor.view().assessment.cause)
        assertTrue(HealthPresentation.present(ServicePhase.Running, monitor.view()).disconnect)
        assertEquals(0L, ledger.snapshot().network.successes)
        advanceTimeBy(100)
        diagnostics.transition(1, RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.UNAVAILABLE, 100)
        assertEquals(0L, ledger.snapshot().network.successes)

        val fresh = monitor.begin(context)
        advanceTimeBy(100)
        assertTrue(monitor.observe(fresh, selectedSuccess))
        assertTrue(ledger.networkConnected(network, fresh.startedAt, 200))
        val recovered = diagnostics.transition(1, RecoveryCause.NONE, RecoveryResult.RECOVERED, 200).single()
        assertEquals(HealthState.Available, monitor.view().assessment.state)
        assertEquals(2L, recovered.count)
        assertEquals(1L, recovered.attempts)
        assertEquals(200L, ledger.snapshot().network.lastDurationMs)
        monitor.stop()
    }

    @Test fun newNetworkProfileAndManualIntentRejectLateProbeAndOldRecoveryScope() = runTest {
        val monitor = HealthMonitor(backgroundScope) { testScheduler.currentTime }
        val ledger = ServiceRecoveryLedger(sessionId)
        val diagnostics = RecoveryDiagnostics(sessionId, minEmissionIntervalMs = 1)
        val preferences = ProfileSelectionPreferences(MemoryStorage())
        remember(preferences)
        val oldIntent = preferences.beginManual(1, "fixture-selector", "fixture-obsolete")!!
        val newIntent = preferences.beginManual(2, "fixture-selector", "fixture-manual")!!
        val oldObservation = monitor.begin(context)
        val restoration = ledger.beginNetworkRestoration(0)!!
        diagnostics.transition(1, RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.FAILED, 0)
        advanceTimeBy(10)
        val changed = context.copy(profile = 2, generation = 2, network = Any(), manualRevision = 1,
            selected = "fixture-manual")
        val current = monitor.begin(changed)
        diagnostics.transition(2, RecoveryCause.PROBE_UNAVAILABLE, RecoveryResult.UNAVAILABLE, 10)

        assertFalse(monitor.observe(oldObservation, selectedSuccess))
        assertTrue(diagnostics.transition(1, RecoveryCause.NONE, RecoveryResult.RECOVERED, 10).isEmpty())
        assertEquals(0L, ledger.snapshot().network.successes)
        assertEquals(HealthState.Checking, monitor.view().assessment.state)
        assertEquals(PreferenceWriteResult.Rejected,
            preferences.acknowledgeManual(oldIntent, acknowledged = true) { true })
        assertEquals(PreferenceWriteResult.Stored,
            preferences.acknowledgeManual(newIntent, acknowledged = true) { true })
        advanceTimeBy(10)
        assertTrue(monitor.observe(current, selectedSuccess))
        assertTrue(ledger.networkConnected(restoration, current.startedAt, 20))
        assertEquals(RecoveryResult.RECOVERED,
            diagnostics.transition(2, RecoveryCause.NONE, RecoveryResult.RECOVERED, 20).single().result)
        assertEquals(changed, monitor.view().context)
        assertEquals("fixture-manual", preferences.read(2, "fixture-selector")?.target)
        assertEquals("fixture-preferred", preferences.read(1, "fixture-selector")?.target)
        monitor.stop()
    }

    @Test fun explicitStopClosesAllPendingEvidenceWithoutErasingManualPreference() = runTest {
        val monitor = HealthMonitor(backgroundScope) { testScheduler.currentTime }
        val ledger = ServiceRecoveryLedger(sessionId)
        val diagnostics = RecoveryDiagnostics(sessionId, minEmissionIntervalMs = 1)
        val storage = MemoryStorage()
        val preferences = ProfileSelectionPreferences(storage)
        val preferred = remember(preferences)
        val policy = PreferredReturnPolicy()
        val preferredContext = returnContext(preferred)
        policy.observe(preferredContext, successfulReturnSample(0), 0)
        val observation = monitor.begin(context)
        val service = ledger.beginServiceRestoration(0)!!
        val network = ledger.beginNetworkRestoration(0)!!
        diagnostics.transition(1, RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.FAILED, 0)
        advanceTimeBy(10)
        monitor.stop()
        assertTrue(ledger.stop(PlannedStop.USER, 10))
        assertEquals(RecoveryResult.CANCELLED, diagnostics.stop(10).single().result)
        policy.reset()
        advanceTimeBy(10)
        assertFalse(monitor.observe(observation, selectedSuccess))
        assertFalse(ledger.serviceRunning(service, 20))
        assertFalse(ledger.networkConnected(network, 20, 20))
        assertTrue(diagnostics.transition(1, RecoveryCause.NONE, RecoveryResult.RECOVERED, 20).isEmpty())
        assertFalse(policy.observe(preferredContext.copy(active = false), successfulReturnSample(20), 20).eligible)
        assertEquals(HealthState.Off, monitor.view().assessment.state)
        assertEquals(1L, ledger.snapshot().network.cancelled)
        assertEquals(preferred, ProfileSelectionPreferences(storage).read(1, "fixture-selector"))
    }

    @Test fun matchedGuardRestartCanRestoreServiceWhileSelectedNetworkRemainsUnverified() = runTest {
        advanceTimeBy(120_000)
        val previous = ServiceSessionMarker(sessionId, 42, "how.vpn.howl", 1_000_000, 100_000)
        val assessment = ServiceRecoveryLedger.assessPrevious(previous,
            listOf(ProcessExitRecord(42, "how.vpn.howl", 1_010_000, ExitReason.LOW_MEMORY)),
            43, "how.vpn.howl", 1_020_000, 120_000)
        assertEquals(ExitAssessmentKind.MATCHED, assessment.kind)
        assertEquals(ExitReason.LOW_MEMORY, assessment.reason)
        assertEquals(ExitAssessmentKind.UNKNOWN, ServiceRecoveryLedger.assessPrevious(previous,
            emptyList(), 42, "how.vpn.howl", 1_020_000, 120_000).kind)
        val monitor = HealthMonitor(backgroundScope) { testScheduler.currentTime }
        val ledger = ServiceRecoveryLedger("22345678-1234-4234-8234-123456789abc", SessionStartKind.GUARD_RESTART)
        val diagnostics = RecoveryDiagnostics(ledger.sessionId, minEmissionIntervalMs = 1)
        val service = ledger.beginServiceRestoration(120_000)!!
        ledger.beginNetworkRestoration(120_000)
        val observation = monitor.begin(context.copy(owner = ledger.sessionId))
        diagnostics.transition(1, RecoveryCause.PROCESS_EXIT, RecoveryResult.FAILED, 120_000)
        advanceTimeBy(100)
        assertTrue(ledger.serviceRunning(service, 120_100))
        assertTrue(monitor.observe(observation, HealthEvidence(physical = ProbeFinding.Responded,
            systemDns = DnsFinding.Resolved, tls = ProbeFinding.Responded)))
        assertEquals(HealthState.Unverified, monitor.view().assessment.state)
        assertEquals(100L, ledger.snapshot().service.lastDurationMs)
        assertEquals(0L, ledger.snapshot().network.successes)
        assertTrue(ledger.snapshot().network.pending)
        assertEquals(RecoveryResult.CANCELLED, diagnostics.stop(120_100).single().result)
        monitor.stop()
    }

    @Test fun dozeGapExpiresHealthAndResetsContinuousPreferredWindow() = runTest {
        val monitor = HealthMonitor(backgroundScope) { testScheduler.currentTime }
        val preferred = remember(ProfileSelectionPreferences(MemoryStorage()))
        val policy = PreferredReturnPolicy()
        val preferredContext = returnContext(preferred)
        val fallback = context.copy(selected = "fixture-reserve")
        monitor.setTemporaryFallback(true)
        for (at in 0L..500_000L step 100_000L) {
            advanceTimeBy(at - testScheduler.currentTime)
            monitor.observe(monitor.begin(fallback), selectedSuccess)
            assertFalse(policy.observe(preferredContext, successfulReturnSample(at), at).eligible)
        }
        advanceTimeBy(120_001)
        runCurrent()
        assertEquals(HealthState.Unverified, monitor.state.value.assessment.state)
        assertEquals(HealthCause.EvidenceExpired, monitor.view().assessment.cause)
        val decision = policy.observe(preferredContext, successfulReturnSample(620_001), 620_001)
        assertFalse(decision.eligible)
        assertEquals(620_001L, decision.continuousSinceMs)
        assertEquals(1, decision.successfulSamples)
        assertTrue(monitor.view().temporaryFallback)
        monitor.stop()
    }

    @Test fun onlyContinuousTenMinutesMakesReturnEligibleWithoutApplyingSelectionItself() = runTest {
        val monitor = HealthMonitor(backgroundScope) { testScheduler.currentTime }
        val storage = MemoryStorage()
        val preferences = ProfileSelectionPreferences(storage)
        val preferred = remember(preferences)
        val originalStoredValue = storage.value
        val policy = PreferredReturnPolicy()
        val preferredContext = returnContext(preferred)
        val fallback = context.copy(selected = "fixture-reserve")
        monitor.setTemporaryFallback(true)
        var decision = PreferredReturnDecision()
        for (at in 0L..600_000L step 100_000L) {
            advanceTimeBy(at - testScheduler.currentTime)
            assertTrue(monitor.observe(monitor.begin(fallback), selectedSuccess))
            decision = policy.observe(preferredContext, successfulReturnSample(at), at)
            if (at < 600_000) assertFalse(decision.eligible)
        }
        assertTrue(decision.eligible)
        assertEquals(0L, decision.continuousSinceMs)
        assertEquals(7, decision.successfulSamples)
        assertEquals("fixture-reserve", monitor.view().context?.selected)
        assertEquals(HealthState.Available, monitor.view().assessment.state)
        assertTrue(monitor.view().temporaryFallback)
        assertEquals(originalStoredValue, storage.value)
        assertEquals(preferred, ProfileSelectionPreferences(storage).read(1, "fixture-selector"))
        monitor.stop()
    }

    @Test fun temporaryEmergencyChoiceNeverBecomesPersistedManualPreferenceInTheModelTrace() = runTest {
        val storage = MemoryStorage()
        val preferences = ProfileSelectionPreferences(storage)
        assertNull(preferences.read(1, "fixture-selector"))
        val preferred = remember(preferences)
        val beforeEmergency = storage.value
        val monitor = HealthMonitor(backgroundScope) { testScheduler.currentTime }
        val diagnostics = RecoveryDiagnostics(sessionId, minEmissionIntervalMs = 1)
        val policy = PreferredReturnPolicy()
        val original = monitor.begin(context)
        assertTrue(monitor.observe(original, HealthEvidence(selected = ProbeFinding.Failed)))
        diagnostics.transition(1, RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.FAILED, 0, attempted = true)
        monitor.setTemporaryFallback(true)
        advanceTimeBy(100)
        val fallback = monitor.begin(context.copy(selected = "fixture-reserve"))
        assertFalse(monitor.observe(original, selectedSuccess))
        assertTrue(monitor.observe(fallback, selectedSuccess))
        assertFalse(policy.observe(returnContext(preferred), successfulReturnSample(100), 100).eligible)
        assertEquals(RecoveryResult.RECOVERED,
            diagnostics.transition(1, RecoveryCause.NONE, RecoveryResult.RECOVERED, 100).single().result)
        assertEquals(beforeEmergency, storage.value)
        assertFalse(storage.value!!.contains("fixture-reserve"))
        assertEquals("fixture-preferred", ProfileSelectionPreferences(storage).read(1, "fixture-selector")?.target)
        assertTrue(monitor.view().temporaryFallback)
        monitor.stop()
    }
}
