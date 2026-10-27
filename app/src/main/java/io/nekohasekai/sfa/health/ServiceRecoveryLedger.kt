package io.nekohasekai.sfa.health

import java.util.UUID

enum class PlannedStop { ACTIVE, USER, REVOKE, FAILURE, RELOAD }
enum class SessionStartKind { USER, STICKY, GUARD_RESTART, CONFIG_RELOAD }
/** Fixed application codes. Android reason integers are mapped only by the Android adapter. */
enum class ExitReason { UNKNOWN, EXIT_SELF, SIGNALED, LOW_MEMORY, CRASH, NATIVE_CRASH, ANR, INITIALIZATION_FAILURE, PERMISSION_CHANGE, EXCESSIVE_RESOURCE, USER_REQUESTED, USER_STOPPED, DEPENDENCY_DIED, OTHER, FREEZER, PACKAGE_STATE_CHANGE, PACKAGE_UPDATED }
enum class ExitAssessmentKind { UNKNOWN, UNSUPPORTED, PLANNED, MATCHED }

/** Separate from profile data. Never include this marker or its encoded value in diagnostics. */
data class ServiceSessionMarker(val sessionId: String, val pid: Int, val processName: String,
    val startedWallMs: Long, val startedElapsedMs: Long, val plannedStop: PlannedStop = PlannedStop.ACTIVE,
    val startKind: SessionStartKind = SessionStartKind.USER) {
    override fun toString() = "ServiceSessionMarker(plannedStop=$plannedStop,startKind=$startKind)"

    companion object {
        fun create(pid: Int, processName: String, startedWallMs: Long, startedElapsedMs: Long,
            startKind: SessionStartKind = SessionStartKind.USER) = ServiceSessionMarker(
            UUID.randomUUID().toString(), pid, processName, startedWallMs, startedElapsedMs,
            startKind = startKind)
    }
}

/** Strict, bounded v1 codec; a bad/unknown version is absent evidence, never a raw error. */
object ServiceSessionMarkerCodec {
    private const val MAX_CHARS = 384
    private val sessionPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val processPattern = Regex("[A-Za-z0-9_.:]{1,160}")

    internal fun canonicalSessionId(id: String): Boolean = sessionPattern.matches(id)

    internal fun valid(marker: ServiceSessionMarker): Boolean = canonicalSessionId(marker.sessionId) &&
        marker.pid > 0 && processPattern.matches(marker.processName) &&
        marker.startedWallMs > 0 && marker.startedElapsedMs >= 0 && marker.startedWallMs >= marker.startedElapsedMs

    fun encode(marker: ServiceSessionMarker): String? {
        if (!valid(marker)) return null
        return listOf("1", marker.sessionId, marker.pid.toString(), marker.processName,
            marker.startedWallMs.toString(), marker.startedElapsedMs.toString(), marker.plannedStop.name,
            marker.startKind.name).joinToString("|").takeIf { it.length <= MAX_CHARS }
    }

    fun decode(raw: String?): ServiceSessionMarker? {
        if (raw == null || raw.length > MAX_CHARS) return null
        val fields = raw.split('|')
        if (fields.size != 8 || fields[0] != "1") return null
        val marker = ServiceSessionMarker(fields[1], fields[2].toIntOrNull() ?: return null, fields[3],
            fields[4].toLongOrNull() ?: return null, fields[5].toLongOrNull() ?: return null,
            PlannedStop.entries.firstOrNull { it.name == fields[6] } ?: return null,
            SessionStartKind.entries.firstOrNull { it.name == fields[7] } ?: return null)
        return marker.takeIf { encode(it) == raw }
    }
}

/** Intentionally cannot hold OS descriptions, traces or exception messages. */
data class ProcessExitRecord(val pid: Int, val processName: String, val timestampWallMs: Long, val reason: ExitReason) {
    override fun toString() = "ProcessExitRecord(reason=$reason)"
}
data class ExitAssessment(val kind: ExitAssessmentKind = ExitAssessmentKind.UNKNOWN,
    val reason: ExitReason = ExitReason.UNKNOWN, val plannedStop: PlannedStop = PlannedStop.ACTIVE)
enum class RestorationKind { SERVICE, NETWORK }
data class RestorationTicket(val sessionId: String, val kind: RestorationKind, val sequence: Long, val startedElapsedMs: Long)
data class RestorationMetrics(val attempts: Long = 0, val successes: Long = 0, val cancelled: Long = 0,
    val lastDurationMs: Long? = null, val totalDurationMs: Long = 0, val pending: Boolean = false)
data class LifecycleRecoverySnapshot(val startKind: SessionStartKind, val plannedStop: PlannedStop = PlannedStop.ACTIVE,
    val service: RestorationMetrics = RestorationMetrics(), val network: RestorationMetrics = RestorationMetrics())

/**
 * One in-memory instance per service session, called serially by the owner.
 * Durations start when restoration is requested, never at a previous process's birth.
 * Core startup completes SERVICE only. NETWORK needs a successful current request ticket.
 */
class ServiceRecoveryLedger(val sessionId: String, val startKind: SessionStartKind = SessionStartKind.USER) {
    init { require(ServiceSessionMarkerCodec.canonicalSessionId(sessionId)) { "Invalid service session ID" } }

    private var lastNowMs = -1L
    private var sequence = 0L
    private var plannedStop = PlannedStop.ACTIVE
    private var service = RestorationMetrics()
    private var network = RestorationMetrics()
    private var serviceTicket: RestorationTicket? = null
    private var networkTicket: RestorationTicket? = null

    private fun acceptTime(nowMs: Long): Boolean {
        if (nowMs < 0 || nowMs < lastNowMs) return false
        lastNowMs = nowMs
        return true
    }

    private fun begin(kind: RestorationKind, nowMs: Long): RestorationTicket? {
        if (plannedStop != PlannedStop.ACTIVE || !acceptTime(nowMs)) return null
        (if (kind == RestorationKind.SERVICE) serviceTicket else networkTicket)?.let { return it }
        if (sequence == Long.MAX_VALUE) return null
        val ticket = RestorationTicket(sessionId, kind, ++sequence, nowMs)
        if (kind == RestorationKind.SERVICE) {
            serviceTicket = ticket
            service = service.copy(attempts = increment(service.attempts), pending = true)
        } else {
            networkTicket = ticket
            network = network.copy(attempts = increment(network.attempts), pending = true)
        }
        return ticket
    }

    fun beginServiceRestoration(nowMs: Long): RestorationTicket? = begin(RestorationKind.SERVICE, nowMs)
    fun beginNetworkRestoration(nowMs: Long): RestorationTicket? = begin(RestorationKind.NETWORK, nowMs)

    fun serviceRunning(ticket: RestorationTicket, nowMs: Long): Boolean {
        if (plannedStop != PlannedStop.ACTIVE || ticket != serviceTicket || ticket.kind != RestorationKind.SERVICE ||
            !acceptTime(nowMs)) return false
        service = completed(service, ticket, nowMs)
        serviceTicket = null
        return true
    }

    /** Successful request START: after this restoration began, and at most 120s old. */
    fun networkConnected(ticket: RestorationTicket, checkedElapsedMs: Long, nowMs: Long): Boolean {
        if (plannedStop != PlannedStop.ACTIVE || ticket != networkTicket || ticket.kind != RestorationKind.NETWORK ||
            checkedElapsedMs < ticket.startedElapsedMs || checkedElapsedMs > nowMs ||
            nowMs - checkedElapsedMs > MAX_REQUEST_AGE_MS || !acceptTime(nowMs)) return false
        network = completed(network, ticket, nowMs)
        networkTicket = null
        return true
    }

    fun stop(reason: PlannedStop, nowMs: Long): Boolean {
        if (reason == PlannedStop.ACTIVE || plannedStop != PlannedStop.ACTIVE || !acceptTime(nowMs)) return false
        plannedStop = reason
        if (serviceTicket != null) service = service.copy(cancelled = increment(service.cancelled), pending = false)
        if (networkTicket != null) network = network.copy(cancelled = increment(network.cancelled), pending = false)
        serviceTicket = null
        networkTicket = null
        return true
    }

    fun snapshot(): LifecycleRecoverySnapshot = LifecycleRecoverySnapshot(startKind, plannedStop, service, network)

    private fun completed(metrics: RestorationMetrics, ticket: RestorationTicket, nowMs: Long): RestorationMetrics {
        val duration = (nowMs - ticket.startedElapsedMs).coerceAtMost(MAX_DURATION_MS)
        val total = if (Long.MAX_VALUE - metrics.totalDurationMs < duration) Long.MAX_VALUE else metrics.totalDurationMs + duration
        return metrics.copy(successes = increment(metrics.successes), lastDurationMs = duration,
            totalDurationMs = total, pending = false)
    }

    companion object {
        private const val MAX_RECORDS = 32
        private const val MAX_RECORD_AGE_MS = 86_400_000L
        private const val BOOT_OFFSET_TOLERANCE_MS = 2_000L
        private const val MAX_DURATION_MS = 604_800_000L
        private const val MAX_REQUEST_AGE_MS = 120_000L
        private fun increment(value: Long) = if (value == Long.MAX_VALUE) value else value + 1

        /**
         * A null sticky intent or absent service is not a diagnosis. A marker and a recent
         * record must agree on PID, process, session interval and the wall/elapsed boot offset.
         * Clock shifts/reboots fail closed; even a same-PID restart remains unknown.
         */
        fun assessPrevious(previous: ServiceSessionMarker?, records: List<ProcessExitRecord>,
            currentPid: Int, currentProcessName: String, nowWallMs: Long, nowElapsedMs: Long,
            historySupported: Boolean = true): ExitAssessment {
            val unknown = ExitAssessment()
            if (previous != null && ServiceSessionMarkerCodec.valid(previous) && previous.plannedStop != PlannedStop.ACTIVE)
                return ExitAssessment(ExitAssessmentKind.PLANNED, plannedStop = previous.plannedStop)
            if (!historySupported) return ExitAssessment(ExitAssessmentKind.UNSUPPORTED)
            if (previous == null || !ServiceSessionMarkerCodec.valid(previous) || currentPid <= 0 ||
                currentPid == previous.pid || currentProcessName != previous.processName ||
                nowWallMs < previous.startedWallMs || nowElapsedMs < previous.startedElapsedMs) return unknown
            val wallDelta = nowWallMs - previous.startedWallMs
            val elapsedDelta = nowElapsedMs - previous.startedElapsedMs
            val offsetDifference = if (wallDelta >= elapsedDelta) wallDelta - elapsedDelta else elapsedDelta - wallDelta
            if (offsetDifference > BOOT_OFFSET_TOLERANCE_MS) return unknown
            val relevant = records.take(MAX_RECORDS).filter { it.pid == previous.pid && it.processName == previous.processName &&
                it.timestampWallMs >= previous.startedWallMs && it.timestampWallMs <= nowWallMs &&
                nowWallMs - it.timestampWallMs <= MAX_RECORD_AGE_MS }
            val record = relevant.maxByOrNull { it.timestampWallMs } ?: return unknown
            if (relevant.any { it.timestampWallMs == record.timestampWallMs && it.reason != record.reason }) return unknown
            return ExitAssessment(ExitAssessmentKind.MATCHED, record.reason)
        }
    }
}
