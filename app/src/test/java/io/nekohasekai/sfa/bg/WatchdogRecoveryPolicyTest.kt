package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.connections.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WatchdogRecoveryPolicyTest {
    @Test fun confirmedUnreadyOpenConnectCanProbeReserveWithoutThreeCheckCycles() {
        assertTrue(WatchdogRecoveryPolicy.canProbeReserveEarly("openconnect", WatchdogProbeResult.NoResponse,
            WatchdogProbeResult.Reachable, false, 10_000, true))
    }
    @Test fun startupOfflineCaptiveHealthyUnknownOrSupersededCannotTakeFastPath() {
        val r = WatchdogProbeResult.Reachable; val n = WatchdogProbeResult.NoResponse; val u = WatchdogProbeResult.Unavailable
        assertFalse(WatchdogRecoveryPolicy.canProbeReserveEarly("openconnect", n, r, false, 9_999, true))
        assertFalse(WatchdogRecoveryPolicy.canProbeReserveEarly("openconnect", n, n, false, 30_000, true))
        assertFalse(WatchdogRecoveryPolicy.canProbeReserveEarly("openconnect", n, u, false, 30_000, true))
        assertFalse(WatchdogRecoveryPolicy.canProbeReserveEarly("openconnect", n, r, true, 30_000, true))
        assertFalse(WatchdogRecoveryPolicy.canProbeReserveEarly("openconnect", n, r, false, 30_000, false))
        assertFalse(WatchdogRecoveryPolicy.canProbeReserveEarly("openconnect", r, r, false, 30_000, true))
        assertFalse(WatchdogRecoveryPolicy.canProbeReserveEarly("openconnect", u, r, false, 30_000, true))
        assertFalse(WatchdogRecoveryPolicy.canProbeReserveEarly("vless", n, r, false, 30_000, true))
    }
    @Test fun twoIndependentFreshFailuresConfirmSelectedPathWithoutParsingErrorText() = runBlocking {
        val urls = mutableListOf<String>(); var closes = 0
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply {
                assertEquals("fixture selected", tag); synchronized(urls) { urls.add(url) }
                return ProbeReply.fromCore(0, "fixture ordinary dial failure")
            }
            override fun close() { synchronized(urls) { closes++ } }
        } })
        assertEquals(WatchdogProbeResult.NoResponse, WatchdogSelectedPathProbe(transport).confirm("fixture selected"))
        assertEquals(ConnectionChecks.urls.reversed(), urls)
        withTimeout(2000) { while (closes != 2) delay(1) }
    }
    @Test fun secondIndependentTargetSuccessIncludingZeroDelayPreventsFastRecovery() = runBlocking {
        var calls = 0
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int) = ProbeReply(0, ++calls == 1)
            override fun close() {}
        } })
        assertEquals(WatchdogProbeResult.Reachable, WatchdogSelectedPathProbe(transport).confirm("fixture"))
        assertEquals(2, calls)
    }
    @Test fun unavailableCommandClientIsNotMisclassifiedAsUnreadyEndpoint() = runBlocking {
        val probe = WatchdogSelectedPathProbe(ProbeTransport(factory = { error("private raw error") }))
        assertEquals(WatchdogProbeResult.Unavailable, probe.confirm("fixture"))
    }
    @Test fun observationExpiresAndEveryIdentityChangeDiscardsTheDecision() {
        val owner = Any(); val id = WatchdogIdentity(owner, 1, "group", "selected", "node", 2)
        val observation = WatchdogObservation(id, 1000)
        assertTrue(observation.current(id, 30_999))
        assertFalse(observation.current(id, 31_001))
        assertFalse(observation.current(id, 999))
        listOf(id.copy(owner = Any()), id.copy(profile = 3), id.copy(selector = "other"), id.copy(selection = "other"),
            id.copy(node = "other"), id.copy(generation = 3), id.copy(network = Any())).forEach { assertFalse(observation.current(it, 2000)) }
    }
    @Test fun acknowledgingOwnSelectionStillRequiresSameSessionProfileAndDeadline() {
        val id = WatchdogIdentity(Any(), 1, "group", "old", "old", 2)
        val observation = WatchdogObservation(id, 1000)
        assertTrue(observation.sessionCurrent(id.copy(selection = "reserve", node = "reserve"), 2000))
        assertFalse(observation.sessionCurrent(id.copy(profile = 2), 2000))
        assertFalse(observation.sessionCurrent(id.copy(generation = 3), 2000))
        assertFalse(observation.sessionCurrent(id, 31_001))
    }
    @Test fun expiredEvidenceCanBeReportedButCannotAuthorizeASelection() {
        val id = WatchdogIdentity(Any(), 1, "group", "node", "node", 2)
        val observation = WatchdogObservation(id, 1000)
        assertTrue(observation.belongsToSession(id))
        assertFalse(observation.sessionCurrent(id, 31_001))
        assertFalse(observation.belongsToSession(id.copy(profile = 2)))
        assertFalse(observation.belongsToSession(id.copy(owner = Any())))
    }
}
