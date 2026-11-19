package io.nekohasekai.sfa.connections

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class SwitchOutcome { Switched, Unchanged, NoResponse, Stale, ProbeError, CommitError }

/** Shared manual/watchdog selection policy. Native transport is injected. */
class SoftSwitch(private val probeBudgetMs: Long = 9000) {
    data class Ticket(val revision: Long, val manual: Boolean)
    private val commitGate = Mutex()
    private var revision = 0L
    private var manualRevision: Long? = null
    private var pauseDepth = 0
    init { require(probeBudgetMs > 0) }
    @Synchronized fun manual(): Ticket = Ticket(++revision, true).also { manualRevision = it.revision }
    @Synchronized fun automatic(): Ticket? = if (manualRevision == null && pauseDepth == 0) Ticket(revision, false) else null
    @Synchronized fun finish(ticket: Ticket) { if (ticket.manual && manualRevision == ticket.revision) manualRevision = null }
    @Synchronized fun invalidate() { revision++; manualRevision = null }
    @Synchronized fun pause() { pauseDepth++; invalidate() }
    @Synchronized fun resume() { check(pauseDepth > 0); pauseDepth--; invalidate() }
    fun track(ticket: Ticket, job: Job) { job.invokeOnCompletion { finish(ticket) } }
    @Synchronized fun valid(ticket: Ticket): Boolean = pauseDepth == 0 && ticket.revision == revision &&
        if (ticket.manual) manualRevision == ticket.revision else manualRevision == null

    suspend fun recoverCore(ticket: Ticket, action: suspend () -> Unit) = commitGate.withLock {
        if (!ticket.manual && valid(ticket)) action()
    }

    suspend fun select(ticket: Ticket, target: String, expected: String, current: () -> String?,
        stillCurrent: () -> Boolean = { true }, followLocalManualIntent: Boolean = false,
        probe: suspend (String) -> Int?, commit: suspend () -> Unit): SwitchOutcome {
        val followSelection = ticket.manual && followLocalManualIntent
        fun active() = valid(ticket) && stillCurrent() &&
            (if (followSelection) current() != null else current() == expected)
        suspend fun commitSelection(success: SwitchOutcome) = try {
            commit()
            if (valid(ticket) && stillCurrent()) success else SwitchOutcome.Stale
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (active()) SwitchOutcome.CommitError else SwitchOutcome.Stale }
        currentCoroutineContext().ensureActive()
        if (!active()) return SwitchOutcome.Stale
        if (followSelection) {
            val settled = commitGate.withLock {
                when {
                    !active() -> SwitchOutcome.Stale
                    // Reassert explicit intent even if a preceding core operation has not
                    // published its updated group selection to the UI yet.
                    target == current() -> commitSelection(SwitchOutcome.Unchanged)
                    else -> null
                }
            }
            if (settled != null) return settled
        } else if (target == expected) return SwitchOutcome.Unchanged
        val delay = try { withTimeoutOrNull(probeBudgetMs) { probe(target) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return if (active()) SwitchOutcome.ProbeError else SwitchOutcome.Stale }
        currentCoroutineContext().ensureActive()
        if (!active()) return SwitchOutcome.Stale
        if (delay == null || delay < 0) return SwitchOutcome.NoResponse
        return commitGate.withLock {
            currentCoroutineContext().ensureActive()
            if (!active()) return@withLock SwitchOutcome.Stale
            commitSelection(if (followSelection && target == current()) SwitchOutcome.Unchanged else SwitchOutcome.Switched)
        }
    }
}

object SoftSwitching { val choices = SoftSwitch() }
