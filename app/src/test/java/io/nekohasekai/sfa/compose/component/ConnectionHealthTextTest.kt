package io.nekohasekai.sfa.compose.component

import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.health.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionHealthTextTest {
    private fun snapshot(state: HealthState, cause: HealthCause = HealthCause.None,
        systemCause: HealthCause = HealthCause.None) = HealthSnapshot(assessment = HealthAssessment(state, cause, systemCause))

    @Test fun runningRecoveryKeepsDisconnectWithoutPositiveColour() {
        val text = connectionHealthText(Status.Started, snapshot(HealthState.Recovering))
        assertEquals(R.string.health_recovering, text.label)
        assertTrue(text.disconnect)
        assertFalse(text.verified)
        assertEquals(listOf(R.string.health_recovering_hint), text.hints)
    }

    @Test fun onlyCurrentLocalAvailableIsPositive() {
        val current = snapshot(HealthState.Available)
        assertTrue(connectionHealthText(Status.Started, current).verified)
        assertEquals(R.string.health_available, connectionHealthText(Status.Started, current).label)
        assertEquals(listOf(R.string.health_available_hint), connectionHealthText(Status.Started, current).hints)
        for (status in listOf(Status.Stopped, Status.Starting, Status.Stopping)) {
            assertFalse(connectionHealthText(status, current).verified)
        }
        for (state in HealthState.entries.filter { it != HealthState.Available }) {
            assertFalse(connectionHealthText(Status.Started, snapshot(state)).verified)
        }
    }

    @Test fun disabledMonitoringAndRemoteCannotBorrowLocalSuccessOrCause() {
        val current = snapshot(HealthState.Available, systemCause = HealthCause.SystemNameResolution)
        for (text in listOf(connectionHealthText(Status.Started, current, monitoring = false),
            connectionHealthText(Status.Started, current, remote = true))) {
            assertEquals(R.string.health_unverified, text.label)
            assertFalse(text.verified)
            assertTrue(text.disconnect)
        }
        assertEquals(listOf(R.string.health_unverified_hint),
            connectionHealthText(Status.Started, current, monitoring = false).hints)
        assertEquals(listOf(R.string.health_remote_hint),
            connectionHealthText(Status.Started, current, remote = true).hints)
    }

    @Test fun expiredEvidenceExplainsWhyItIsUnverifiedWithoutClaimingFailure() {
        val text = connectionHealthText(Status.Started, snapshot(HealthState.Unverified, HealthCause.EvidenceExpired))
        assertEquals(R.string.health_unverified, text.label)
        assertEquals(listOf(R.string.health_expired_hint), text.hints)
        assertFalse(text.verified)
    }

    @Test fun systemResolverFindingIsSeparateFromSelectedServerFailure() {
        val text = connectionHealthText(Status.Started,
            snapshot(HealthState.RequestsFailed, HealthCause.SelectedRequestsFailed, HealthCause.SystemNameResolution))
        assertEquals(R.string.health_requests_failed, text.label)
        assertEquals(listOf(R.string.health_requests_failed_hint, R.string.health_system_dns_hint), text.hints)
    }

    @Test fun typedTlsAndHttpsFindingsDoNotBecomeDns() {
        val tls = connectionHealthText(Status.Started,
            snapshot(HealthState.CheckUnavailable, HealthCause.ProbeUnavailable, HealthCause.TlsHandshakeFailed))
        assertEquals(listOf(R.string.health_check_unavailable_hint, R.string.health_system_tls_hint), tls.hints)
        val https = connectionHealthText(Status.Started,
            snapshot(HealthState.CheckUnavailable, HealthCause.ProbeUnavailable, HealthCause.HttpsRequestFailed))
        assertEquals(listOf(R.string.health_check_unavailable_hint, R.string.health_system_https_hint), https.hints)
    }

    @Test fun lifecycleOwnsActionEvenWithoutSuccessfulProbe() {
        assertTrue(connectionHealthText(Status.Starting, HealthSnapshot()).disconnect)
        assertTrue(connectionHealthText(Status.Started, HealthSnapshot()).disconnect)
        assertFalse(connectionHealthText(Status.Stopping, HealthSnapshot()).disconnect)
        assertFalse(connectionHealthText(Status.Stopped, HealthSnapshot()).disconnect)
        assertEquals(R.string.health_connecting, connectionHealthText(Status.Starting, HealthSnapshot()).label)
        assertEquals(R.string.health_off, connectionHealthText(Status.Stopped, HealthSnapshot()).label)
    }

    @Test fun stoppedProfileErrorIsVisibleAndDoesNotOfferDisconnect() {
        val text = connectionHealthText(Status.Stopped, snapshot(HealthState.ProfileProblem, HealthCause.ProfileInvalid))
        assertEquals(R.string.health_profile_problem, text.label)
        assertFalse(text.disconnect)
        assertFalse(text.verified)
    }

    @Test fun temporaryFallbackIsOnlyShownForThisMonitoredLocalSession() {
        val fallback = snapshot(HealthState.Available).copy(temporaryFallback = true)
        assertEquals(listOf(R.string.health_available_hint, R.string.health_temporary_fallback_hint),
            connectionHealthText(Status.Started, fallback).hints)
        for (text in listOf(connectionHealthText(Status.Started, fallback, remote = true),
            connectionHealthText(Status.Started, fallback, monitoring = false),
            connectionHealthText(Status.Starting, fallback), connectionHealthText(Status.Stopped, fallback))) {
            assertFalse(text.hints.contains(R.string.health_temporary_fallback_hint))
        }
    }
}
