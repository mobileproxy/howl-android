package io.nekohasekai.sfa.connections

import kotlinx.coroutines.CancellationException
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
    init { require(probeBudgetMs > 0) }
    @Synchronized fun manual(): Ticket = Ticket(++revision, true).also { manualRevision = it.revision }
    @Synchronized fun automatic(): Ticket? = if (manualRevision == null) Ticket(revision, false) else null
    @Synchronized fun finish(ticket: Ticket) { if (ticket.manual && manualRevision == ticket.revision) manualRevision = null }
    @Synchronized fun invalidate() { revision++; manualRevision = null }
    @Synchronized fun valid(ticket: Ticket): Boolean = ticket.revision == revision &&
        if (ticket.manual) manualRevision == ticket.revision else manualRevision == null

    suspend fun select(ticket: Ticket, target: String, expected: String, current: () -> String?,
        stillCurrent: () -> Boolean = { true }, probe: suspend (String) -> Int?, commit: suspend () -> Unit): SwitchOutcome {
        fun active() = valid(ticket) && stillCurrent() && current() == expected
        currentCoroutineContext().ensureActive()
        if (!active()) return SwitchOutcome.Stale
        if (target == expected) return SwitchOutcome.Unchanged
        val delay = try { withTimeoutOrNull(probeBudgetMs) { probe(target) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return if (active()) SwitchOutcome.ProbeError else SwitchOutcome.Stale }
        currentCoroutineContext().ensureActive()
        if (!active()) return SwitchOutcome.Stale
        if (delay == null || delay < 0) return SwitchOutcome.NoResponse
        return commitGate.withLock {
            currentCoroutineContext().ensureActive()
            if (!active()) return@withLock SwitchOutcome.Stale
            try {
                commit()
                if (valid(ticket) && stillCurrent()) SwitchOutcome.Switched else SwitchOutcome.Stale
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (active()) SwitchOutcome.CommitError else SwitchOutcome.Stale }
        }
    }
}

object SoftSwitching { val choices = SoftSwitch() }
