package io.nekohasekai.sfa.bg

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

data class ReserveMeasurement(val tag: String, val delay: Int?, val completed: Boolean)

/** Bounded comparison window after the first success; slow neighbours cannot delay a usable reserve. */
suspend fun probeReserveBatch(tags: List<String>, timeoutMs: Long, compareMs: Long = 250,
    preferred: Set<String> = emptySet(),
    probe: suspend (String) -> Int?): List<ReserveMeasurement> = coroutineScope {
    val replies = Channel<ReserveMeasurement>(tags.size.coerceAtLeast(1))
    val jobs = tags.map { tag -> launch {
        val result = try {
            withTimeoutOrNull(timeoutMs) { ReserveMeasurement(tag, probe(tag), true) }
                ?: ReserveMeasurement(tag, null, false)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { ReserveMeasurement(tag, null, false) }
        replies.send(result)
    } }
    val results = mutableListOf<ReserveMeasurement>()
    try {
        while (results.size < tags.size) {
            val next = replies.receive(); results.add(next)
            if (next.delay != null) {
                withTimeoutOrNull(compareMs) {
                    while (results.size < tags.size) results.add(replies.receive())
                }
                if (next.tag !in preferred && results.none { it.tag in preferred && it.delay != null }) {
                    withTimeoutOrNull((750 - compareMs).coerceAtLeast(0)) {
                        while (preferred.any { tag -> results.none { it.tag == tag } }) {
                            val reply = replies.receive()
                            results.add(reply)
                            if (reply.tag in preferred && reply.delay != null) break
                        }
                    }
                }
                break
            }
        }
        results.toList()
    } finally {
        // The private channel is collected with the scope. Closing it before cancelled
        // producers finish can turn a successful probe into ClosedSendChannelException.
        jobs.forEach { it.cancel() }
    }
}

/** Mix two preferred-city probes with backups, then rank by fresh latency. */
internal fun reserveGroup(remaining: List<String>, preferred: Set<String>): List<String> =
    (remaining.filter { it in preferred }.take(2) + remaining.filterNot { it in preferred } + remaining)
        .distinct().take(6)

internal fun rankReserves(results: List<ReserveMeasurement>, preferred: Set<String>): List<ReserveMeasurement> {
    val alive = results.filter { it.delay != null }.sortedBy { it.delay }
    val fastest = alive.firstOrNull()?.delay ?: return emptyList()
    return alive.sortedBy { it.tag !in preferred || it.delay!! - fastest > 250 }
}

internal enum class ReserveSearchOutcome { Recovered, Exhausted, Deadline, Unavailable, Superseded }

/** Each measured candidate is consumed once. Probes cancelled by the comparison window
 * get another opportunity after the untouched candidates. */
internal suspend fun searchWatchdogReserves(ordered: List<String>, preferred: Set<String>, current: () -> Boolean,
    measure: suspend (List<String>, Set<String>) -> List<ReserveMeasurement>,
    apply: suspend (ReserveMeasurement) -> WatchdogProbeResult,
    consumed: (String) -> Unit = {}, budgetMs: Long = 60_000): ReserveSearchOutcome = withTimeoutOrNull(budgetMs) {
    val remaining = ordered.distinct().toMutableList()
    val deferred = mutableListOf<String>()
    while (remaining.isNotEmpty() || deferred.isNotEmpty()) {
        if (!current()) return@withTimeoutOrNull ReserveSearchOutcome.Superseded
        val group = if (remaining.isNotEmpty()) reserveGroup(remaining, preferred) else reserveGroup(deferred, preferred)
        val results = measure(group, group.filter { it in preferred }.toSet())
        if (!current()) return@withTimeoutOrNull ReserveSearchOutcome.Superseded
        results.filter { it.delay == null }.forEach { consumed(it.tag) }
        val answered = results.map { it.tag }.toSet()
        deferred.removeAll(group.toSet())
        deferred.addAll(group.filter { it in remaining && it !in answered })
        remaining.removeAll(group.toSet())
        for (candidate in rankReserves(results, preferred)) {
            if (!current()) return@withTimeoutOrNull ReserveSearchOutcome.Superseded
            when (apply(candidate)) {
                WatchdogProbeResult.Reachable -> return@withTimeoutOrNull ReserveSearchOutcome.Recovered
                WatchdogProbeResult.NoResponse -> consumed(candidate.tag)
                WatchdogProbeResult.Unavailable -> return@withTimeoutOrNull ReserveSearchOutcome.Unavailable
            }
        }
    }
    ReserveSearchOutcome.Exhausted
} ?: ReserveSearchOutcome.Deadline
