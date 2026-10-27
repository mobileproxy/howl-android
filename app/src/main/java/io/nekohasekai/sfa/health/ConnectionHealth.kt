package io.nekohasekai.sfa.health

/** Service state and the explicitly limited scope of a current control probe are separate. */
enum class HealthState { Off, Connecting, Disconnecting, Checking, Available, Recovering, NoNetwork, NoInternet, Captive, RequestsFailed, Partial, CheckUnavailable, ProfileProblem, Unverified }
enum class HealthCause { None, NoPhysicalNetwork, CaptiveNetwork, PhysicalRequestsFailed, SelectedRequestsFailed, SystemNameResolution, HttpsRequestFailed, TlsHandshakeFailed, ProbeUnavailable, UnsupportedExit, ProfileInvalid, EvidenceExpired }
enum class HealthScope { None, SelectedExitResponse }
enum class ProbeFinding { NotChecked, Responded, Failed, Unavailable }
enum class DnsFinding { Unknown, Resolved, Failed }
enum class HttpFinding { Unknown, Expected, UnexpectedStatus, HandshakeFailure, TransportFailure }
enum class ServicePhase { Off, Starting, Running, Stopping }
data class HealthContext(val owner: Any, val profile: Long, val generation: Long, val selector: String?,
    val selected: String?, val network: Any?, val manualRevision: Long)
data class HealthEvidence(val hasNetwork: Boolean = true, val captiveHint: Boolean = false,
    val physical: ProbeFinding = ProbeFinding.NotChecked, val selected: ProbeFinding = ProbeFinding.NotChecked,
    val systemDns: DnsFinding = DnsFinding.Unknown, val systemHttp: HttpFinding = HttpFinding.Unknown,
    val tls: ProbeFinding = ProbeFinding.NotChecked, val oldTraffic: Boolean = false,
    val vpnTarget: Boolean = true, val profileError: Boolean = false)
data class HealthAssessment(val state: HealthState = HealthState.Unverified, val cause: HealthCause = HealthCause.None,
    val systemCause: HealthCause = HealthCause.None, val scope: HealthScope = HealthScope.None, val httpStatusKnown: Boolean = false)
data class HealthObservation(val context: HealthContext, val revision: Long, val startedAt: Long)
data class HealthSnapshot(val context: HealthContext? = null, val assessment: HealthAssessment = HealthAssessment(),
    val checkedAt: Long? = null, val checking: Boolean = false, val temporaryFallback: Boolean = false)
object ConnectionHealth {
    fun classify(evidence: HealthEvidence): HealthAssessment {
        // This resolver/HTTPS path can intentionally bypass VPN under the profile policy.
        // It is useful evidence about the system check, never proof of profile DNS failure.
        val systemCause = when {
            evidence.systemDns == DnsFinding.Failed -> HealthCause.SystemNameResolution
            evidence.systemHttp == HttpFinding.HandshakeFailure -> HealthCause.TlsHandshakeFailed
            evidence.systemHttp in setOf(HttpFinding.UnexpectedStatus, HttpFinding.TransportFailure) -> HealthCause.HttpsRequestFailed
            else -> HealthCause.None
        }
        fun result(state: HealthState, cause: HealthCause = HealthCause.None, scope: HealthScope = HealthScope.None) =
            HealthAssessment(state, cause, systemCause, scope)
        return when {
            evidence.profileError -> result(HealthState.ProfileProblem, HealthCause.ProfileInvalid)
            !evidence.hasNetwork -> result(HealthState.NoNetwork, HealthCause.NoPhysicalNetwork)
            !evidence.vpnTarget -> result(HealthState.Unverified, HealthCause.UnsupportedExit)
            // Opaque native success says only that this selected exit answered. It
            // does not prove an HTTP status, all routes, every site or UDP traffic.
            evidence.selected == ProbeFinding.Responded -> result(HealthState.Available, scope = HealthScope.SelectedExitResponse)
            evidence.captiveHint -> result(HealthState.Captive, HealthCause.CaptiveNetwork)
            evidence.selected == ProbeFinding.Unavailable -> result(HealthState.CheckUnavailable, HealthCause.ProbeUnavailable)
            evidence.selected == ProbeFinding.Failed && evidence.oldTraffic -> result(HealthState.Partial, HealthCause.SelectedRequestsFailed)
            evidence.selected == ProbeFinding.Failed && evidence.physical == ProbeFinding.Failed -> result(HealthState.NoInternet, HealthCause.PhysicalRequestsFailed)
            evidence.selected == ProbeFinding.Failed -> result(HealthState.RequestsFailed, HealthCause.SelectedRequestsFailed)
            else -> result(HealthState.Unverified)
        }
    }
}
data class HealthPresentation(val state: HealthState, val cause: HealthCause, val disconnect: Boolean) {
    companion object {
        fun present(service: ServicePhase, snapshot: HealthSnapshot, monitoring: Boolean = true, remote: Boolean = false): HealthPresentation {
            val disconnect = service in setOf(ServicePhase.Running, ServicePhase.Starting)
            val assessment = when {
                remote -> HealthAssessment()
                service == ServicePhase.Off -> if (snapshot.assessment.state == HealthState.ProfileProblem) snapshot.assessment else HealthAssessment(HealthState.Off)
                service == ServicePhase.Starting -> HealthAssessment(HealthState.Connecting)
                service == ServicePhase.Stopping -> HealthAssessment(HealthState.Disconnecting)
                !monitoring || snapshot.assessment.state == HealthState.Off -> HealthAssessment()
                else -> snapshot.assessment
            }
            return HealthPresentation(assessment.state, assessment.cause, disconnect)
        }
    }
}
