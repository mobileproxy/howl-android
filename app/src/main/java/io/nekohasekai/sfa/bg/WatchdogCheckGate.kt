package io.nekohasekai.sfa.bg

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** One running observation and one newest pending signal; no event queue behind network IO. */
class WatchdogCheckGate(scope: CoroutineScope, initialDelayMs: Long,
    intervalMs: () -> Long, private val check: suspend (String) -> Unit) {
    private val signals = Channel<String>(Channel.CONFLATED)
    init { require(initialDelayMs >= 0) }
    private val job = scope.launch {
            delay(initialDelayMs)
            var trigger = signals.tryReceive().getOrNull() ?: "цикл"
            while (isActive) {
                try { check(trigger) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* next event/periodic observation remains possible */ }
                trigger = withTimeoutOrNull(intervalMs().coerceAtLeast(1)) { signals.receive() } ?: "цикл"
            }
        }
    fun request(trigger: String) { if (job.isActive) signals.trySend(trigger) }
    fun close() { job.cancel(); signals.cancel() }
}
