package io.nekohasekai.sfa.connections

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

enum class CheckState { Queued, Checking, RetryWaiting, Retrying, Working, NoResponse, Error, Cancelled }
enum class CheckPhase { Idle, Running, Done, Stopped, NetworkChanged, Failed, Busy }
data class CheckTarget(val tag: String, val type: String) {
    val checkable: Boolean get() = tag != "auto" && type.lowercase() !in setOf("selector", "urltest", "direct", "reject", "block", "dns")
    val location: String get() = tag.substringBeforeLast(" · ", tag).trimStart { !it.isLetterOrDigit() }.ifEmpty { tag }
}
data class CheckResult(val tag: String, val state: CheckState, val delayMs: Int? = null,
    val attempt: Int = 0, val checkedAt: Long? = null) {
    val finished: Boolean get() = state in setOf(CheckState.Working, CheckState.NoResponse, CheckState.Error, CheckState.Cancelled)
}
data class CheckSnapshot(val results: Map<String, CheckResult> = emptyMap(), val active: Set<String> = emptySet()) {
    val total: Int get() = active.size
    val completed: Int get() = active.count { results[it]?.let { r -> r.finished && r.state != CheckState.Cancelled } == true }
    val working: Int get() = active.count { results[it]?.state == CheckState.Working }
    val allWorking: Int get() = results.values.count { it.state == CheckState.Working }
    val repeating: Int get() = active.count { results[it]?.state in setOf(CheckState.RetryWaiting, CheckState.Retrying) }
    val unresolved: List<String> get() = results.values.filter { it.state != CheckState.Working }.map { it.tag }
}

/** Main-thread reducer. No raw exceptions, URLs or historical core delays enter this state. */
class ConnectionCheckSession {
    var generation = 0L
        private set
    var snapshot = CheckSnapshot()
        private set
    fun begin(tags: List<String>, reset: Boolean = false): Long {
        val items = if (reset) linkedMapOf() else snapshot.results.toMutableMap()
        tags.distinct().forEach { items[it] = CheckResult(it, CheckState.Queued) }
        snapshot = CheckSnapshot(items, tags.toSet())
        return ++generation
    }
    fun apply(generation: Long, result: CheckResult): Boolean {
        if (generation != this.generation || result.tag !in snapshot.active || snapshot.results[result.tag]?.finished == true) return false
        snapshot = snapshot.copy(results = snapshot.results + (result.tag to result))
        return true
    }
    fun cancel() {
        snapshot = snapshot.copy(results = snapshot.results.mapValues { (_, r) ->
            if (r.tag in snapshot.active && !r.finished) CheckResult(r.tag, CheckState.Cancelled) else r
        })
        generation++
    }
    fun clear() { cancel(); snapshot = CheckSnapshot() }
}

/** A successful 0 ms response is valid in libbox; null means no response. */
object ConnectionChecks {
    const val PARALLELISM = 4
    val urls = listOf("http://cp.cloudflare.com/generate_204", "http://www.gstatic.com/generate_204")
    suspend fun run(tags: List<String>, measure: suspend (String, Int) -> Int?,
        report: (CheckResult) -> Unit, retryPauseMs: Long = 2000, attemptDeadlineMs: Long = 14000,
        now: () -> Long = System::currentTimeMillis): List<CheckResult> = coroutineScope {
        require(retryPauseMs >= 0 && attemptDeadlineMs > 0)
        val slots = Semaphore(PARALLELISM)
        val finished = java.util.concurrent.ConcurrentHashMap<String, CheckResult>()
        fun emit(result: CheckResult) { if (result.finished) finished[result.tag] = result; report(result) }
        tags.distinct().map { tag -> launch {
            try {
                slots.withPermit {
                    for (attempt in 1..2) {
                        coroutineContext.ensureActive()
                        emit(CheckResult(tag, if (attempt == 1) CheckState.Checking else CheckState.Retrying, attempt = attempt))
                        val delayMs = withTimeoutOrNull(attemptDeadlineMs) { measure(tag, attempt) }
                        coroutineContext.ensureActive()
                        if (delayMs != null) {
                            check(delayMs >= 0)
                            emit(CheckResult(tag, CheckState.Working, delayMs, attempt, now()))
                            break
                        }
                        if (attempt == 1) {
                            emit(CheckResult(tag, CheckState.RetryWaiting, attempt = attempt))
                            delay(retryPauseMs)
                        } else emit(CheckResult(tag, CheckState.NoResponse, attempt = attempt, checkedAt = now()))
                    }
                }
            } catch (cancel: CancellationException) {
                emit(CheckResult(tag, CheckState.Cancelled)); throw cancel
            } catch (_: Exception) {
                emit(CheckResult(tag, CheckState.Error, checkedAt = now()))
            }
        } }.joinAll()
        finished.values.toList()
    }
}

/** Shared only by explicit GUI diagnostics and manual connection checks, not the watchdog. */
object ManualProbeGate {
    private val busy = java.util.concurrent.atomic.AtomicBoolean()
    fun acquire(): java.io.Closeable? {
        if (!busy.compareAndSet(false, true)) return null
        val released = java.util.concurrent.atomic.AtomicBoolean()
        return java.io.Closeable { if (released.compareAndSet(false, true)) busy.set(false) }
    }
}
