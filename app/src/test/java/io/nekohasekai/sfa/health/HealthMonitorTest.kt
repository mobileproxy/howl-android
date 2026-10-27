package io.nekohasekai.sfa.health

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthMonitorTest {
    private val context = HealthContext(Any(), 1, 0, "fixture", "fixture-exit", Any(), 0)
    private val success = HealthEvidence(selected = ProbeFinding.Responded)
    @Test fun flowExpiresSuccessfulEvidenceWithoutAnExternalRefresh() = runTest {
        val monitor = HealthMonitor(this) { testScheduler.currentTime }
        assertTrue(monitor.observe(monitor.begin(context), success))
        assertEquals(HealthState.Available, monitor.state.value.assessment.state)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(HealthState.Unverified, monitor.state.value.assessment.state)
        monitor.stop()
    }
    @Test fun oldExpiryCannotClearANewProfileObservation() = runTest {
        val monitor = HealthMonitor(this) { testScheduler.currentTime }
        monitor.observe(monitor.begin(context), success)
        advanceTimeBy(90_000)
        monitor.observe(monitor.begin(context.copy(profile = 2)), success)
        advanceTimeBy(30_001); runCurrent()
        assertEquals(HealthState.Available, monitor.state.value.assessment.state)
        assertEquals(2L, monitor.state.value.context?.profile)
        monitor.stop()
    }
    @Test fun stoppingCancelsExpiryAndRejectsLateSuccess() = runTest {
        val monitor = HealthMonitor(this) { testScheduler.currentTime }
        val observation = monitor.begin(context)
        monitor.stop()
        assertFalse(monitor.observe(observation, success))
        advanceTimeBy(180_000); runCurrent()
        assertEquals(HealthState.Off, monitor.state.value.assessment.state)
    }
    @Test fun anUnfinishedObservationCannotDisplayCheckingForever() = runTest {
        val monitor = HealthMonitor(this) { testScheduler.currentTime }
        monitor.begin(context)
        advanceTimeBy(30_001); runCurrent()
        assertEquals(HealthState.CheckUnavailable, monitor.state.value.assessment.state)
        assertFalse(monitor.state.value.checking)
        monitor.stop()
    }
    @Test fun anOldNetworkFailureAlsoBecomesUnverifiedWithoutFreshEvidence() = runTest {
        val monitor = HealthMonitor(this) { testScheduler.currentTime }
        monitor.observe(monitor.begin(context), HealthEvidence(hasNetwork = false))
        assertEquals(HealthState.NoNetwork, monitor.state.value.assessment.state)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(HealthState.Unverified, monitor.state.value.assessment.state)
        monitor.stop()
    }
    @Test fun idleMonitorDoesNotPollTheClockEverySecond() = runTest {
        var reads = 0
        val monitor = HealthMonitor(this) { reads++; testScheduler.currentTime }
        monitor.observe(monitor.begin(context), success)
        advanceTimeBy(119_000); runCurrent()
        assertTrue("Freshness uses one expiry, not periodic background polling", reads < 12)
        monitor.stop()
    }
    @Test fun anOldResultCannotOverwriteTheFlowAfterANewerCheck() = runTest {
        val monitor = HealthMonitor(this) { testScheduler.currentTime }
        val old = monitor.begin(context)
        val current = monitor.begin(context.copy(manualRevision = 1))
        assertFalse(monitor.observe(old, success))
        assertTrue(monitor.observe(current, success))
        assertEquals(1L, monitor.state.value.context?.manualRevision)
        monitor.stop()
    }
}
