package io.nekohasekai.sfa.diagnostics

import io.nekohasekai.sfa.health.*
import org.junit.Assert.*
import org.junit.Test

class ReliabilityReportTest {
    private val secret = "secret-token user@example.invalid https://private.invalid 192.0.2.42"
    private fun report() = ReliabilityReport(HealthSnapshot(HealthContext(secret, 12, 3, secret, secret, secret, 5),
        HealthAssessment(HealthState.Partial, HealthCause.SelectedRequestsFailed, HealthCause.SystemNameResolution),
        checkedAt = 50, temporaryFallback = true), 100,
        LifecycleRecoverySnapshot(SessionStartKind.GUARD_RESTART, network = RestorationMetrics(3, 1, 0, 42)),
        ExitAssessment(ExitAssessmentKind.MATCHED, ExitReason.LOW_MEMORY),
        List(10) { RecoveryDiagnosticSummary(RecoveryCause.SELECTED_REQUESTS_FAILED, RecoveryResult.FAILED, 50, 100, 3, 1) })
    @Test fun exactEvidenceAndServiceNetworkCountersRemainDistinct() {
        val json = report().json()
        assertTrue(json.has("health"))
        assertEquals("Partial", json.optJSONObject("health")?.optString("state"))
        assertEquals("SystemNameResolution", json.optJSONObject("health")?.optString("systemCause"))
        assertEquals(3L, json.optJSONObject("serviceRecovery")?.optLong("networkAttempts"))
        assertEquals(0L, json.optJSONObject("serviceRecovery")?.optLong("serviceSuccesses"))
    }
    @Test fun onlyAllowlistedCodesNumbersAndFlagsAreExported() {
        val json = report().json()
        assertTrue(json.has("recovery"))
        val text = json.toString()
        listOf("secret-token", "example.invalid", "private.invalid", "192.0.2.42", "selector", "sessionId", "processName").forEach { assertFalse(text.contains(it)) }
        assertEquals(8, json.optJSONArray("recovery")?.length())
        assertEquals(50L, json.optJSONObject("health")?.optLong("checkedAgeMs"))
        assertTrue(json.optJSONObject("health")?.optBoolean("temporaryFallback") == true)
    }
    @Test fun missingOrFutureEvidenceHasNoInventedAgeOrLifecycle() {
        val json = report().copy(health = report().health.copy(checkedAt = 101), lifecycle = null).json()
        assertTrue(json.has("health"))
        assertTrue(json.optJSONObject("health")?.isNull("checkedAgeMs") == true)
        assertTrue(json.isNull("serviceRecovery"))
    }
}
