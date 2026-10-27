package io.nekohasekai.sfa.compose.component

import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.health.*

/** One resource-only presentation for the dashboard, status bar and notification. */
data class ConnectionHealthText(val label: Int, val hints: List<Int>, val verified: Boolean, val disconnect: Boolean)

fun connectionHealthText(status: Status, snapshot: HealthSnapshot, monitoring: Boolean = true,
    remote: Boolean = false): ConnectionHealthText {
    val phase = when (status) {
        Status.Stopped -> ServicePhase.Off
        Status.Starting -> ServicePhase.Starting
        Status.Started -> ServicePhase.Running
        Status.Stopping -> ServicePhase.Stopping
    }
    val presentation = HealthPresentation.present(phase, snapshot, monitoring, remote)
    val (label, hint) = when (presentation.state) {
        HealthState.Off -> R.string.health_off to R.string.health_off_hint
        HealthState.Connecting -> R.string.health_connecting to R.string.health_connecting_hint
        HealthState.Disconnecting -> R.string.health_disconnecting to R.string.health_disconnecting_hint
        HealthState.Checking -> R.string.health_checking to R.string.health_checking_hint
        HealthState.Available -> R.string.health_available to R.string.health_available_hint
        HealthState.Recovering -> R.string.health_recovering to R.string.health_recovering_hint
        HealthState.NoNetwork -> R.string.health_no_network to R.string.health_no_network_hint
        HealthState.NoInternet -> R.string.health_no_internet to R.string.health_no_internet_hint
        HealthState.Captive -> R.string.health_captive to R.string.health_captive_hint
        HealthState.RequestsFailed -> R.string.health_requests_failed to R.string.health_requests_failed_hint
        HealthState.Partial -> R.string.health_partial to R.string.health_partial_hint
        HealthState.CheckUnavailable -> R.string.health_check_unavailable to R.string.health_check_unavailable_hint
        HealthState.ProfileProblem -> R.string.health_profile_problem to R.string.health_profile_problem_hint
        HealthState.Unverified -> R.string.health_unverified to when {
            remote -> R.string.health_remote_hint
            presentation.cause == HealthCause.EvidenceExpired -> R.string.health_expired_hint
            presentation.cause == HealthCause.UnsupportedExit -> R.string.health_unsupported_hint
            else -> R.string.health_unverified_hint
        }
    }
    val localRunning = phase == ServicePhase.Running && monitoring && !remote
    val systemHint = if (localRunning) when (snapshot.assessment.systemCause) {
        HealthCause.SystemNameResolution -> R.string.health_system_dns_hint
        HealthCause.TlsHandshakeFailed -> R.string.health_system_tls_hint
        HealthCause.HttpsRequestFailed -> R.string.health_system_https_hint
        else -> null
    } else null
    val fallbackHint = if (localRunning && snapshot.temporaryFallback) R.string.health_temporary_fallback_hint else null
    return ConnectionHealthText(label, listOfNotNull(hint, systemHint, fallbackHint),
        localRunning && presentation.state == HealthState.Available, presentation.disconnect)
}
