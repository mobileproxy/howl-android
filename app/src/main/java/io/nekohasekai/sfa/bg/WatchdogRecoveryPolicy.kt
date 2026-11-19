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
    companion object { const val DEADLINE_MS = 80_000L }
    fun current(other: WatchdogIdentity, now: Long): Boolean = identity == other && now - startedAt in 0..DEADLINE_MS
    // Selection may change as a result of OUR acknowledged commit. Everything else still matches.
    fun belongsToSession(other: WatchdogIdentity): Boolean = identity.copy(selection = other.selection, node = other.node) == other
    fun sessionCurrent(other: WatchdogIdentity, now: Long): Boolean =
        belongsToSession(other) && now - startedAt in 0..DEADLINE_MS
}

/** Three exhausted searches before the first reload. A reload keeps the backoff until verified recovery. */
class WatchdogRestartBackoff {
    private var exhausted = 0
    private var reloads = 0
    private var dueAt: Long? = null

    @Synchronized fun exhausted(now: Long) {
        if (++exhausted >= 3) scheduleIfIdle(now)
    }

    @Synchronized fun scheduleIfIdle(now: Long) {
        if (dueAt == null) dueAt = now + delayMs()
    }

    @Synchronized fun pending() = dueAt != null
    @Synchronized fun due(now: Long) = dueAt?.let { now >= it } == true

    @Synchronized fun started() {
        reloads++
        exhausted = 0
        dueAt = null
    }

    @Synchronized fun reset() {
        exhausted = 0
        reloads = 0
        dueAt = null
    }

    private fun delayMs() = 30_000L shl reloads.coerceAtMost(2)
}

/** The selector proof rules out a bypass-only success. HTTPS exercises the Android VPN path. */
internal fun combineRouteProbes(selected: WatchdogProbeResult, https: WatchdogProbeResult): WatchdogProbeResult =
    when {
        selected == WatchdogProbeResult.NoResponse -> WatchdogProbeResult.NoResponse
        selected == WatchdogProbeResult.Unavailable -> WatchdogProbeResult.Unavailable
        else -> https
    }

class WatchdogSelectedPathProbe(private val transport: ProbeTransport, private val timeoutMs: Long = 9400) {
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
