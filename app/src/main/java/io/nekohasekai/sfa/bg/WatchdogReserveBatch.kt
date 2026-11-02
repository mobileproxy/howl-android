package io.nekohasekai.sfa.bg

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

data class ReserveMeasurement(val tag: String, val delay: Int?, val completed: Boolean)

/** Bounded comparison window after the first success; slow neighbours cannot delay a usable reserve. */
suspend fun probeReserveBatch(tags: List<String>, timeoutMs: Long, compareMs: Long = 250,
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
