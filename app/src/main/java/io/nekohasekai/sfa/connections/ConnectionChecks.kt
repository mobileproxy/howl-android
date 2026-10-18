package io.nekohasekai.sfa.connections

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

enum class CheckState { Queued, Checking, RetryWaiting, Retrying, Working, NoResponse, Error, Cancelled, NotCompleted }
enum class CheckPhase { Idle, Running, Done, Stopped, NetworkChanged, Failed, Busy, TimedOut }
enum class CheckIssue { None, RunDeadline, RequestDidNotStop }
data class CheckTarget(val tag: String, val type: String) {
    val checkable: Boolean get() = tag != "auto" && type.lowercase() !in setOf("selector", "urltest", "direct", "reject", "block", "dns")
    val location: String get() = tag.substringBeforeLast(" · ", tag).trimStart { !it.isLetterOrDigit() }.ifEmpty { tag }
}
data class CheckResult(val tag: String, val state: CheckState, val delayMs: Int? = null,
    val attempt: Int = 0, val checkedAt: Long? = null, val startedAt: Long? = null,
    val timeoutMs: Long? = null, val issue: CheckIssue = CheckIssue.None) {
    val finished: Boolean get() = state in setOf(CheckState.Working, CheckState.NoResponse, CheckState.Error, CheckState.Cancelled, CheckState.NotCompleted)
}
data class CheckSnapshot(val results: Map<String, CheckResult> = emptyMap(), val active: Set<String> = emptySet(),
    val elapsedMs: Long = 0, val clockAt: Long = 0) {
    val total: Int get() = active.size
    val completed: Int get() = active.count { results[it]?.let { r -> r.finished && r.state !in setOf(CheckState.Cancelled, CheckState.NotCompleted) } == true }
    val working: Int get() = active.count { results[it]?.state == CheckState.Working }
    val allWorking: Int get() = results.values.count { it.state == CheckState.Working }
    val repeating: Int get() = active.count { results[it]?.state in setOf(CheckState.RetryWaiting, CheckState.Retrying) }
    val running: Int get() = active.count { results[it]?.state in setOf(CheckState.Checking, CheckState.Retrying) }
    val queued: Int get() = active.count { results[it]?.state == CheckState.Queued }
    val waitingToRetry: Int get() = active.count { results[it]?.state == CheckState.RetryWaiting }
    val firstPassCompleted: Int get() = active.count { results[it]?.state in setOf(CheckState.Working, CheckState.NoResponse,
        CheckState.Error, CheckState.RetryWaiting, CheckState.Retrying) }
    val retryTotal: Int get() = active.count { results[it]?.let { r -> r.attempt == 2 || r.state == CheckState.RetryWaiting } == true }
    val retryCompleted: Int get() = active.count { results[it]?.let { r -> r.attempt == 2 && r.finished
        && r.state !in setOf(CheckState.Cancelled, CheckState.NotCompleted) } == true }
    val confirming: Boolean get() = total > 0 && firstPassCompleted == total && retryTotal > 0
    val progressTotal: Int get() = (if (confirming) retryTotal else total).coerceAtLeast(1)
    val progressDone: Int get() = if (confirming) retryCompleted else firstPassCompleted
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
    fun tick(clockAt: Long, elapsedMs: Long) { snapshot = snapshot.copy(clockAt = clockAt, elapsedMs = elapsedMs.coerceAtLeast(0)) }
}

/** A successful 0 ms response is valid in libbox; null means no response. */
object ConnectionChecks {
    const val PARALLELISM = 8
    const val FIRST_ATTEMPT_MS = 5000L
    const val RETRY_ATTEMPT_MS = 9000L
    const val RUN_DEADLINE_MS = 60000L
    val urls = listOf("http://cp.cloudflare.com/generate_204", "http://www.gstatic.com/generate_204")
    suspend fun run(tags: List<String>, measure: suspend (String, Int) -> Int?,
        report: (CheckResult) -> Unit, retryPauseMs: Long = 2000, attemptDeadlineMs: Long = FIRST_ATTEMPT_MS,
        now: () -> Long = System::currentTimeMillis,
        retryDeadlineMs: Long = if (attemptDeadlineMs == FIRST_ATTEMPT_MS) RETRY_ATTEMPT_MS else attemptDeadlineMs,
        totalDeadlineMs: Long = RUN_DEADLINE_MS): List<CheckResult> = coroutineScope {
        require(retryPauseMs >= 0 && attemptDeadlineMs > 0 && retryDeadlineMs > 0 && totalDeadlineMs > 0)
        val targets = tags.distinct()
        val latest = ConcurrentHashMap(targets.associateWith { CheckResult(it, CheckState.Queued) })
        val repeats = ConcurrentLinkedQueue<String>()
        fun emit(result: CheckResult) {
            latest.computeIfPresent(result.tag) { _, previous ->
                if (previous.finished) previous else { report(result); result }
            }
        }
        suspend fun checkTarget(tag: String, attempt: Int) {
            try {
                currentCoroutineContext().ensureActive()
                val budget = if (attempt == 1) attemptDeadlineMs else retryDeadlineMs
                emit(CheckResult(tag, if (attempt == 1) CheckState.Checking else CheckState.Retrying,
                    attempt = attempt, startedAt = now(), timeoutMs = budget))
                val delayMs = withTimeoutOrNull(budget) { measure(tag, attempt) }
                currentCoroutineContext().ensureActive()
                if (delayMs != null) {
                    require(delayMs >= 0)
                    emit(CheckResult(tag, CheckState.Working, delayMs, attempt, now()))
                } else if (attempt == 1) {
                    emit(CheckResult(tag, CheckState.RetryWaiting, attempt = attempt))
                    repeats.add(tag)
                } else emit(CheckResult(tag, CheckState.NoResponse, attempt = attempt, checkedAt = now()))
            } catch (cancel: CancellationException) {
                currentCoroutineContext().ensureActive()
                emit(CheckResult(tag, CheckState.Error, attempt = attempt, checkedAt = now()))
            } catch (error: Exception) {
                emit(CheckResult(tag, CheckState.Error, attempt = attempt, checkedAt = now(),
                    issue = if (error is ProbeRequestDidNotStopException) CheckIssue.RequestDidNotStop else CheckIssue.None))
            }
        }
        suspend fun phase(items: Collection<String>, attempt: Int) = coroutineScope {
            val queue = ConcurrentLinkedQueue(items)
            List(minOf(PARALLELISM, queue.size)) { launch {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val tag = queue.poll() ?: break
                    checkTarget(tag, attempt)
                }
            } }.joinAll()
        }
        try {
            val completed = withTimeoutOrNull(totalDeadlineMs) {
                phase(targets, 1)
                if (repeats.isNotEmpty()) {
                    delay(retryPauseMs)
                    phase(repeats.toList(), 2)
                }
                true
            } == true
            if (!completed) targets.forEach { tag ->
                emit(CheckResult(tag, CheckState.NotCompleted, attempt = latest.getValue(tag).attempt, issue = CheckIssue.RunDeadline))
            }
        } catch (cancel: CancellationException) {
            targets.forEach { emit(CheckResult(it, CheckState.Cancelled)) }
            throw cancel
        }
        targets.map { latest.getValue(it) }
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
