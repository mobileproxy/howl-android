package io.nekohasekai.sfa.diagnostics

import io.nekohasekai.sfa.health.*
import org.json.JSONObject
import org.json.JSONArray

data class ReliabilityReport(val health: HealthSnapshot, val nowElapsedMs: Long,
    val lifecycle: LifecycleRecoverySnapshot?, val previousExit: ExitAssessment,
    val recovery: List<RecoveryDiagnosticSummary>) {
    fun json(): JSONObject {
        val checked = health.checkedAt
        val age = checked?.takeIf { nowElapsedMs >= it && it >= 0 }?.let { nowElapsedMs - it }
        val result = JSONObject().put("health", JSONObject()
            .put("state", health.assessment.state.name).put("cause", health.assessment.cause.name)
            .put("systemCause", health.assessment.systemCause.name).put("scope", health.assessment.scope.name)
            .put("checkedAgeMs", age?.coerceAtMost(HealthTracker.FRESH_FOR_MS) ?: JSONObject.NULL)
            .put("temporaryFallback", health.temporaryFallback))
        val service = lifecycle?.let {
            JSONObject().put("startKind", it.startKind.name).put("plannedStop", it.plannedStop.name)
                .put("previousExitKind", previousExit.kind.name).put("previousExitReason", previousExit.reason.name)
                .put("serviceAttempts", it.service.attempts).put("serviceSuccesses", it.service.successes)
                .put("serviceCancelled", it.service.cancelled).put("serviceLastDurationMs", it.service.lastDurationMs ?: JSONObject.NULL)
                .put("networkAttempts", it.network.attempts).put("networkSuccesses", it.network.successes)
                .put("networkCancelled", it.network.cancelled).put("networkLastDurationMs", it.network.lastDurationMs ?: JSONObject.NULL)
        }
        result.put("serviceRecovery", service ?: JSONObject.NULL)
        result.put("recovery", JSONArray().apply {
            recovery.takeLast(8).forEach { summary ->
                put(JSONObject().put("cause", summary.cause.name).put("result", summary.result.name)
                    .put("firstElapsedMs", summary.firstElapsedMs).put("lastElapsedMs", summary.lastElapsedMs)
                    .put("count", summary.count).put("attempts", summary.attempts).put("droppedSummaries", summary.droppedSummaries))
            }
        })
        return result
    }
}
