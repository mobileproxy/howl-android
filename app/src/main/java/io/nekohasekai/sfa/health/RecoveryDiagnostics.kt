package io.nekohasekai.sfa.health

import java.util.ArrayDeque

/** Fixed codes only; callers map typed health/OS facts, never raw error text. */
enum class RecoveryCause { NONE, UNKNOWN, NO_PHYSICAL_NETWORK, CAPTIVE_NETWORK, PHYSICAL_REQUESTS_FAILED, SELECTED_REQUESTS_FAILED, SYSTEM_NAME_RESOLUTION, HTTPS_REQUEST_FAILED, TLS_HANDSHAKE_FAILED, PROBE_UNAVAILABLE, UNSUPPORTED_EXIT, PROFILE_INVALID, EVIDENCE_EXPIRED, PROCESS_EXIT }
enum class RecoveryResult { FAILED, UNAVAILABLE, RECOVERED, CANCELLED }
data class RecoveryDiagnosticSummary(val cause: RecoveryCause, val result: RecoveryResult,
    val firstElapsedMs: Long, val lastElapsedMs: Long, val count: Long, val attempts: Long,
    val droppedSummaries: Long = 0) {
    fun allowlistedFields(): Map<String, String> = linkedMapOf("cause" to cause.name, "result" to result.name,
        "firstElapsedMs" to firstElapsedMs.toString(), "lastElapsedMs" to lastElapsedMs.toString(),
        "count" to count.toString(), "attempts" to attempts.toString(), "droppedSummaries" to droppedSummaries.toString())
}

/**
 * Serially owned, per-session aggregation. A monotonically increasing scope revision changes
 * on profile/network/manual intent changes; stale scopes cannot recover or join newer failures.
 * Normal calls emit at most one summary per interval. Changes/recovery finalize into a bounded
 * queue immediately. Stop drains at most maxPendingSummaries once, then closes the instance.
 * Emitted counts are cumulative within an episode, not deltas to be added together.
 */
class RecoveryDiagnostics(sessionId: String, private val minEmissionIntervalMs: Long = 30_000,
    private val maxPendingSummaries: Int = 8) {
    init {
        require(ServiceSessionMarkerCodec.canonicalSessionId(sessionId)) { "Invalid service session ID" }
        require(minEmissionIntervalMs in 1..86_400_000) { "Invalid diagnostic interval" }
        require(maxPendingSummaries in 1..32) { "Invalid diagnostic capacity" }
    }

    private var scope = -1L
    private var lastNowMs = -1L
    private var lastEmissionMs: Long? = null
    private var active: RecoveryDiagnosticSummary? = null
    private var dirty = false
    private var stopped = false
    private var dropped = 0L
    private val pending = ArrayDeque<RecoveryDiagnosticSummary>()

    private fun acceptTime(nowMs: Long): Boolean {
        if (nowMs < 0 || nowMs < lastNowMs) return false
        lastNowMs = nowMs
        return true
    }

    fun transition(scopeRevision: Long, cause: RecoveryCause, result: RecoveryResult, nowMs: Long,
        attempted: Boolean = false): List<RecoveryDiagnosticSummary> {
        if (stopped || scopeRevision < 0 || scopeRevision < scope) return emptyList()
        val terminal = result == RecoveryResult.RECOVERED || result == RecoveryResult.CANCELLED
        if (terminal) {
            if (scopeRevision != scope || active == null || !acceptTime(nowMs)) return emptyList()
            active = active!!.copy(result = result, lastElapsedMs = nowMs)
            dirty = true
            finalizeActive()
        } else {
            if (cause == RecoveryCause.NONE || !acceptTime(nowMs)) return emptyList()
            if (scopeRevision != scope || active?.cause != cause) {
                finalizeActive()
                scope = scopeRevision
                active = RecoveryDiagnosticSummary(cause, result, nowMs, nowMs, 1, if (attempted) 1 else 0)
            } else {
                val old = active!!
                active = old.copy(result = result, lastElapsedMs = nowMs, count = increment(old.count),
                    attempts = if (attempted) increment(old.attempts) else old.attempts)
            }
            dirty = true
        }
        return emitAvailable(nowMs)
    }

    fun drain(nowMs: Long): List<RecoveryDiagnosticSummary> {
        if (stopped || !acceptTime(nowMs)) return emptyList()
        return emitAvailable(nowMs)
    }

    fun stop(nowMs: Long): List<RecoveryDiagnosticSummary> {
        if (stopped || !acceptTime(nowMs)) return emptyList()
        stopped = true
        active?.let {
            active = it.copy(result = RecoveryResult.CANCELLED, lastElapsedMs = nowMs)
            dirty = true
        }
        finalizeActive()
        val summaries = mutableListOf<RecoveryDiagnosticSummary>()
        while (pending.isNotEmpty()) summaries += withDropped(pending.removeFirst())
        return summaries
    }

    private fun finalizeActive() {
        active?.takeIf { dirty }?.let {
            if (pending.size == maxPendingSummaries) {
                pending.removeFirst()
                dropped = increment(dropped)
            }
            pending.addLast(it)
        }
        active = null
        dirty = false
    }

    private fun emitAvailable(nowMs: Long): List<RecoveryDiagnosticSummary> {
        val last = lastEmissionMs
        if (last != null && nowMs - last < minEmissionIntervalMs) return emptyList()
        val summary = if (pending.isNotEmpty()) pending.removeFirst() else active?.takeIf { dirty }?.also { dirty = false }
        if (summary == null) return emptyList()
        lastEmissionMs = nowMs
        return listOf(withDropped(summary))
    }

    private fun withDropped(summary: RecoveryDiagnosticSummary): RecoveryDiagnosticSummary {
        val result = summary.copy(droppedSummaries = dropped)
        dropped = 0
        return result
    }

    private fun increment(value: Long) = if (value == Long.MAX_VALUE) value else value + 1
}
