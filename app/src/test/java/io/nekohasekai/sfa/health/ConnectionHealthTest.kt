package io.nekohasekai.sfa.health

import org.junit.Assert.*
import org.junit.Test

class ConnectionHealthTest {
    private val context = HealthContext(Any(), 1, 0, "fixture-selector", "fixture-exit", Any(), 0)
    private val success = HealthEvidence(selected = ProbeFinding.Responded)
    private fun assessment(evidence: HealthEvidence) = ConnectionHealth.classify(evidence)

    @Test fun successfulTlsAndFailedHttpsDoNotProveProfileDnsFailure() {
        val result = assessment(HealthEvidence(selected = ProbeFinding.Failed, physical = ProbeFinding.Responded,
            tls = ProbeFinding.Responded, systemHttp = HttpFinding.TransportFailure))
        assertEquals(HealthState.RequestsFailed, result.state)
        assertEquals(HealthCause.SelectedRequestsFailed, result.cause)
        assertEquals(HealthCause.HttpsRequestFailed, result.systemCause)
    }
    @Test fun systemBypassSuccessCannotConfirmAnUnmeasuredSelectedExit() {
        val result = assessment(HealthEvidence(systemHttp = HttpFinding.Expected, systemDns = DnsFinding.Resolved))
        assertEquals(HealthState.Unverified, result.state)
        assertEquals(HealthScope.None, result.scope)
    }
    @Test fun selectedExitResponseIsPreciselyScopedAndNotProfileDnsProof() {
        val result = assessment(success.copy(systemDns = DnsFinding.Failed))
        assertEquals(HealthState.Available, result.state)
        assertEquals(HealthScope.SelectedExitResponse, result.scope)
        assertEquals(HealthCause.SystemNameResolution, result.systemCause)
        assertFalse(result.httpStatusKnown)
    }
    @Test fun captiveHintWithoutAConfirmedSelectedResponseIsDistinctFromServerFailure() {
        val result = assessment(HealthEvidence(captiveHint = true, selected = ProbeFinding.Failed))
        assertEquals(HealthState.Captive, result.state)
        assertEquals(HealthCause.CaptiveNetwork, result.cause)
    }
    @Test fun missingPhysicalNetworkIsNotAServerDiagnosis() {
        val result = assessment(HealthEvidence(hasNetwork = false))
        assertEquals(HealthState.NoNetwork, result.state)
        assertEquals(HealthCause.NoPhysicalNetwork, result.cause)
    }
    @Test fun unavailableNativeCheckIsNotAFailedExit() {
        val result = assessment(HealthEvidence(selected = ProbeFinding.Unavailable))
        assertEquals(HealthState.CheckUnavailable, result.state)
        assertEquals(HealthCause.ProbeUnavailable, result.cause)
    }
    @Test fun oldTrafficDoesNotConfirmFreshRequests() {
        val result = assessment(HealthEvidence(selected = ProbeFinding.Failed, oldTraffic = true, physical = ProbeFinding.Responded))
        assertEquals(HealthState.Partial, result.state)
        assertEquals(HealthCause.SelectedRequestsFailed, result.cause)
    }
    @Test fun confirmedPhysicalAndSelectedFailuresHaveASeparateNetworkCause() {
        val result = assessment(HealthEvidence(selected = ProbeFinding.Failed, physical = ProbeFinding.Failed))
        assertEquals(HealthState.NoInternet, result.state)
        assertEquals(HealthCause.PhysicalRequestsFailed, result.cause)
    }
    @Test fun directOrUnsupportedTargetCannotBecomeAVerifiedVpnExit() {
        assertEquals(HealthState.Unverified, assessment(success.copy(vpnTarget = false)).state)
    }
    @Test fun knownProfileErrorIsNotTurnedIntoANetworkProblem() {
        assertEquals(HealthState.ProfileProblem, assessment(HealthEvidence(profileError = true)).state)
    }
    @Test fun lateSuccessAfterStopDoesNotReviveHealth() {
        val tracker = HealthTracker(); val observation = tracker.begin(context, 0)
        tracker.stop()
        assertFalse(tracker.observe(observation, success, 1))
        assertEquals(HealthState.Off, tracker.view(1).assessment.state)
    }
    @Test fun oldProfileNetworkConfigurationOrManualObservationCannotOverwriteANewOne() {
        listOf(context.copy(profile = 2), context.copy(network = Any()), context.copy(generation = 1),
            context.copy(manualRevision = 1), context.copy(selected = "fixture-new")).forEach { changed ->
            val tracker = HealthTracker(); val old = tracker.begin(context, 0)
            tracker.begin(changed, 1)
            assertFalse(tracker.observe(old, success, 2))
            assertEquals(changed, tracker.view(2).context)
        }
    }
    @Test fun evenSameContextCannotAcceptAnOlderConcurrentCheck() {
        val tracker = HealthTracker(); val old = tracker.begin(context, 0); val newer = tracker.begin(context, 1)
        assertFalse(tracker.observe(old, success, 2))
        assertTrue(tracker.observe(newer, success, 2))
    }
    @Test fun wholeObservationDeadlineAndBackwardsTimeDiscardSuccess() {
        val tracker = HealthTracker(); val old = tracker.begin(context, 100)
        assertFalse(tracker.observe(old, success, 30_101))
        val current = tracker.begin(context, 40_000)
        assertFalse(tracker.observe(current, success, 39_999))
    }
    @Test fun successfulEvidenceExpiresInsteadOfRemainingGreenAfterSleep() {
        val tracker = HealthTracker(); val observation = tracker.begin(context, 0)
        assertTrue(tracker.observe(observation, success, 5))
        assertEquals(HealthState.Available, tracker.view(120_004).assessment.state)
        assertEquals(HealthState.Unverified, tracker.view(120_005).assessment.state)
        assertEquals(HealthCause.EvidenceExpired, tracker.view(120_005).assessment.cause)
    }
    @Test fun recheckKeepsOnlyAStillFreshCurrentSuccess() {
        val tracker = HealthTracker(); tracker.observe(tracker.begin(context, 0), success, 1)
        tracker.begin(context, 90_000)
        assertEquals(HealthState.Available, tracker.view(90_001).assessment.state)
        assertTrue(tracker.view(90_001).checking)
        tracker.begin(context.copy(selected = "fixture-new"), 90_002)
        assertEquals(HealthState.Checking, tracker.view(90_002).assessment.state)
    }
    @Test fun aLateHealingEventCannotHideANewManualChoice() {
        val tracker = HealthTracker(); val old = tracker.begin(context, 0)
        tracker.begin(context.copy(manualRevision = 1), 1)
        assertFalse(tracker.recovering(old, 2))
        assertEquals(HealthState.Checking, tracker.view(2).assessment.state)
    }
    @Test fun serviceAndRemotePresentationCannotInheritLocalSuccessfulEvidence() {
        val tracker = HealthTracker(); tracker.observe(tracker.begin(context, 0), success, 1)
        val snapshot = tracker.view(2)
        assertEquals(HealthState.Off, HealthPresentation.present(ServicePhase.Off, snapshot).state)
        assertEquals(HealthState.Connecting, HealthPresentation.present(ServicePhase.Starting, snapshot).state)
        assertEquals(HealthState.Unverified, HealthPresentation.present(ServicePhase.Running, snapshot, monitoring = false).state)
        assertEquals(HealthState.Unverified, HealthPresentation.present(ServicePhase.Running, snapshot, remote = true).state)
    }
    @Test fun recoveringNeverChangesARunningServiceIntoAConnectAction() {
        val tracker = HealthTracker(); val observation = tracker.begin(context, 0)
        assertTrue(tracker.recovering(observation, 1))
        val presentation = HealthPresentation.present(ServicePhase.Running, tracker.view(2))
        assertEquals(HealthState.Recovering, presentation.state)
        assertTrue(presentation.disconnect)
    }
}
