package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.connections.ProbeTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

object WatchdogRecoveryPolicy {
    fun canProbeReserveEarly(protocol: String?, selected: WatchdogProbeResult, physical: WatchdogProbeResult,
        captive: Boolean, sinceStartMs: Long, current: Boolean): Boolean =
        protocol.equals("openconnect", ignoreCase = true) && selected == WatchdogProbeResult.NoResponse &&
            physical == WatchdogProbeResult.Reachable && !captive &&
            sinceStartMs >= WatchdogRules.MIN_FIRST_CHECK_MS && current
}

data class WatchdogIdentity(val owner: Any, val profile: Long, val selector: String?, val selection: String?,
    val node: String?, val generation: Long, val network: Any? = null)

class WatchdogObservation(val identity: WatchdogIdentity, val startedAt: Long) {
    fun current(other: WatchdogIdentity, now: Long): Boolean = identity == other && now - startedAt in 0..30_000
    // Selection may change as a result of OUR acknowledged commit. Everything else still matches.
    fun belongsToSession(other: WatchdogIdentity): Boolean = identity.copy(selection = other.selection, node = other.node) == other
    fun sessionCurrent(other: WatchdogIdentity, now: Long): Boolean =
        belongsToSession(other) && now - startedAt in 0..30_000
}

class WatchdogSelectedPathProbe(private val transport: ProbeTransport, private val timeoutMs: Long = 6000) {
    private data class Reply(val delay: Int?)
    suspend fun confirm(tag: String): WatchdogProbeResult = try {
        // Attempt 2 tries both independent targets; a local IPC failure is not a failed endpoint.
        when (val reply = withTimeoutOrNull(timeoutMs) { Reply(transport.measure(tag, 2)) }) {
            null -> WatchdogProbeResult.Unavailable
            else -> if (reply.delay == null) WatchdogProbeResult.NoResponse else WatchdogProbeResult.Reachable
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { WatchdogProbeResult.Unavailable }
}
