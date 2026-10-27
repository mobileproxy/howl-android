package io.nekohasekai.sfa.health

import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HealthMonitorFaultTest {
    private val context = HealthContext(Any(), 1, 1, "fixture", "fixture-node", Any(), 1)
    @Test fun validatedProfileFaultSurvivesStoppedServicePresentation() = runTest {
        val monitor = HealthMonitor(backgroundScope) { testScheduler.currentTime }
        monitor.stop(); monitor.profileProblem()
        assertEquals(HealthState.ProfileProblem, HealthPresentation.present(ServicePhase.Off, monitor.view()).state)
    }
    @Test fun lateSuccessCannotEraseCurrentProfileFault() = runTest {
        val monitor = HealthMonitor(backgroundScope) { testScheduler.currentTime }
        val old = monitor.begin(context)
        monitor.profileProblem()
        assertFalse(monitor.observe(old, HealthEvidence(selected = ProbeFinding.Responded)))
        assertEquals(HealthState.ProfileProblem, monitor.view().assessment.state)
    }
    @Test fun nextStartReplacesOldFaultAndDoesNotRetainOldExpiry() = runTest {
        val monitor = HealthMonitor(backgroundScope) { testScheduler.currentTime }
        val old = monitor.begin(context)
        monitor.observe(old, HealthEvidence(selected = ProbeFinding.Responded))
        monitor.profileProblem()
        advanceTimeBy(120_001); runCurrent()
        assertEquals(HealthState.ProfileProblem, monitor.view().assessment.state)
        monitor.begin(context.copy(generation = 2))
        assertEquals(HealthState.Checking, monitor.view().assessment.state)
    }
}
