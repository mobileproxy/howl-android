package io.nekohasekai.sfa.health

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.AppEventLog

/** Serial owner; only fixed codes/counters leave this adapter. Call start on the service IO queue. */
object AndroidLifecycleDiagnostics {
    private var marker: ServiceSessionMarker? = null
    private var ledger: ServiceRecoveryLedger? = null
    private var diagnostics: RecoveryDiagnostics? = null
    private var previous = ExitAssessment()
    private var serviceTicket: RestorationTicket? = null
    private var networkTicket: RestorationTicket? = null
    private var context: HealthContext? = null
    private var revision = 0L
    private val recent = ArrayDeque<RecoveryDiagnosticSummary>()

    @Synchronized fun start(kind: SessionStartKind) {
        val elapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val processName = if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
            else Application.application.packageName
        val prior = ServiceSessionMarkerCodec.decode(Settings.serviceSessionMarker)
        val records = if (Build.VERSION.SDK_INT >= 30) runCatching {
            Application.application.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(null, prior?.pid ?: 0, 32).take(32).map {
                    ProcessExitRecord(it.pid, it.processName, it.timestamp, when (it.reason) {
                        ApplicationExitInfo.REASON_EXIT_SELF -> ExitReason.EXIT_SELF
                        ApplicationExitInfo.REASON_SIGNALED -> ExitReason.SIGNALED
                        ApplicationExitInfo.REASON_LOW_MEMORY -> ExitReason.LOW_MEMORY
                        ApplicationExitInfo.REASON_CRASH -> ExitReason.CRASH
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitReason.NATIVE_CRASH
                        ApplicationExitInfo.REASON_ANR -> ExitReason.ANR
                        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> ExitReason.INITIALIZATION_FAILURE
                        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> ExitReason.PERMISSION_CHANGE
                        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> ExitReason.EXCESSIVE_RESOURCE
                        ApplicationExitInfo.REASON_USER_REQUESTED -> ExitReason.USER_REQUESTED
                        ApplicationExitInfo.REASON_USER_STOPPED -> ExitReason.USER_STOPPED
                        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> ExitReason.DEPENDENCY_DIED
                        ApplicationExitInfo.REASON_OTHER -> ExitReason.OTHER
                        else -> ExitReason.UNKNOWN
                    })
                }
        }.getOrDefault(emptyList()) else emptyList()
        previous = ServiceRecoveryLedger.assessPrevious(prior, records, Process.myPid(), processName,
            wall, elapsed, Build.VERSION.SDK_INT >= 30)
        marker = ServiceSessionMarker.create(Process.myPid(), processName, wall, elapsed, kind)
        persistMarker()
        ledger = ServiceRecoveryLedger(marker!!.sessionId, kind)
        diagnostics = RecoveryDiagnostics(marker!!.sessionId)
        context = null
        revision++
        recent.clear()
        serviceTicket = ledger?.beginServiceRestoration(elapsed)
        networkTicket = ledger?.beginNetworkRestoration(elapsed)
        AppEventLog.log("reliability", "startKind=${kind.name} previousExitKind=${previous.kind.name} previousExitReason=${previous.reason.name}")
    }
    private fun persistMarker() {
        marker?.let { ServiceSessionMarkerCodec.encode(it) }?.let { encoded ->
            runCatching { Settings.serviceSessionMarker = encoded }
        }
    }
    @Synchronized fun serviceRunning() {
        serviceTicket?.let { ledger?.serviceRunning(it, SystemClock.elapsedRealtime()) }
        serviceTicket = null
    }
    @Synchronized fun plannedReload() {
        marker = marker?.copy(plannedStop = PlannedStop.RELOAD)
        persistMarker()
        invalidate()
    }
    @Synchronized fun reloaded() {
        marker = marker?.copy(plannedStop = PlannedStop.ACTIVE)
        persistMarker()
        invalidate()
    }
    @Synchronized fun invalidate() { context = null; revision++ }
    @Synchronized fun observation(observation: HealthObservation, snapshot: HealthSnapshot, attempted: Boolean = false) {
        if (snapshot.context != observation.context) return
        if (context != observation.context) { context = observation.context; revision++ }
        val now = SystemClock.elapsedRealtime()
        val assessment = snapshot.assessment
        val available = assessment.state == HealthState.Available
        val cause = when (assessment.cause) {
            HealthCause.None -> RecoveryCause.NONE
            HealthCause.NoPhysicalNetwork -> RecoveryCause.NO_PHYSICAL_NETWORK
            HealthCause.CaptiveNetwork -> RecoveryCause.CAPTIVE_NETWORK
            HealthCause.PhysicalRequestsFailed -> RecoveryCause.PHYSICAL_REQUESTS_FAILED
            HealthCause.SelectedRequestsFailed -> RecoveryCause.SELECTED_REQUESTS_FAILED
            HealthCause.SystemNameResolution -> RecoveryCause.SYSTEM_NAME_RESOLUTION
            HealthCause.HttpsRequestFailed -> RecoveryCause.HTTPS_REQUEST_FAILED
            HealthCause.TlsHandshakeFailed -> RecoveryCause.TLS_HANDSHAKE_FAILED
            HealthCause.ProbeUnavailable -> RecoveryCause.PROBE_UNAVAILABLE
            HealthCause.UnsupportedExit -> RecoveryCause.UNSUPPORTED_EXIT
            HealthCause.ProfileInvalid -> RecoveryCause.PROFILE_INVALID
            HealthCause.EvidenceExpired -> RecoveryCause.EVIDENCE_EXPIRED
        }
        if (available) {
            networkTicket?.let { ledger?.networkConnected(it, observation.startedAt, now) }
            networkTicket = null
        } else if (networkTicket == null) networkTicket = ledger?.beginNetworkRestoration(now)
        record(diagnostics?.transition(revision, cause,
            if (available) RecoveryResult.RECOVERED else if (assessment.state in setOf(HealthState.Unverified, HealthState.CheckUnavailable)) RecoveryResult.UNAVAILABLE else RecoveryResult.FAILED,
            now, attempted).orEmpty())
    }
    private fun record(summaries: List<RecoveryDiagnosticSummary>) {
        summaries.forEach {
            if (recent.size == 8) recent.removeFirst()
            recent.addLast(it)
            AppEventLog.log("reliability", it.allowlistedFields().entries.joinToString(" ") { (key, value) -> "$key=$value" })
        }
    }
    @Synchronized fun stop(reason: PlannedStop) {
        marker = marker?.copy(plannedStop = reason)
        persistMarker()
        ledger?.stop(reason, SystemClock.elapsedRealtime())
        record(diagnostics?.stop(SystemClock.elapsedRealtime()).orEmpty())
        context = null
        revision++
    }
    @Synchronized fun reportFields(): Map<String, String> {
        val current = ledger?.snapshot() ?: return emptyMap()
        return linkedMapOf("startKind" to current.startKind.name, "plannedStop" to current.plannedStop.name,
            "previousExitKind" to previous.kind.name, "previousExitReason" to previous.reason.name,
            "serviceAttempts" to current.service.attempts.toString(), "serviceSuccesses" to current.service.successes.toString(),
            "serviceCancelled" to current.service.cancelled.toString(), "serviceLastDurationMs" to (current.service.lastDurationMs ?: 0).toString(),
            "networkAttempts" to current.network.attempts.toString(), "networkSuccesses" to current.network.successes.toString(),
            "networkCancelled" to current.network.cancelled.toString(), "networkLastDurationMs" to (current.network.lastDurationMs ?: 0).toString())
    }
    @Synchronized fun summaries(): List<RecoveryDiagnosticSummary> = recent.toList()
    @Synchronized fun report() = io.nekohasekai.sfa.diagnostics.ReliabilityReport(
        HealthRuntime.view(), SystemClock.elapsedRealtime(), ledger?.snapshot(), previous, recent.toList())
}
