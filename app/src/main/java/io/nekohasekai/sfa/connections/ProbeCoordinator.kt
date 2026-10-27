package io.nekohasekai.sfa.connections

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ProbePriority { Active, Recovery, ManualScan, Background }
class ProbeAdmissionBusyException : IllegalStateException("Probe admission is busy")
class OwnedProbeLease internal constructor(private val release: () -> Unit) : java.io.Closeable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}

/** Only app-owned probes participate. Independent native periodic groups are outside this cap. */
class ProbeCoordinator(private val maximumWaiters: Int = 64) {
    init { require(maximumWaiters > 0) }
    private class Waiter(val priority: ProbePriority, val continuation: CancellableContinuation<OwnedProbeLease>) {
        var lease: OwnedProbeLease? = null
    }
    private val lock = Any()
    private val waiters = ArrayList<Waiter>()
    private var owned = 0
    private var nonActive = 0

    /** Cancellable/deadline-bound by the caller, before it submits any worker or creates a client. */
    suspend fun acquire(priority: ProbePriority): OwnedProbeLease = suspendCancellableCoroutine { continuation ->
        val waiter = Waiter(priority, continuation)
        continuation.invokeOnCancellation {
            val lease = synchronized(lock) { waiters.remove(waiter); waiter.lease }
            lease?.close()
        }
        var overflow = false
        val grants = synchronized(lock) {
            waiters.removeAll { !it.continuation.isActive }
            if (!continuation.isActive) emptyList()
            else if (admits(priority) && waiters.none { admits(it.priority) && it.priority.ordinal <= priority.ordinal })
                listOf(grant(waiter))
            else if (waiters.size >= maximumWaiters) { overflow = true; emptyList() }
            else { waiters.add(waiter); drain() }
        }
        grants.forEach { it.continuation.resume(it.lease!!) }
        if (overflow) continuation.resumeWithException(ProbeAdmissionBusyException())
    }

    private fun admits(priority: ProbePriority) = owned < 8 && (priority == ProbePriority.Active || nonActive < 6)
    private fun grant(waiter: Waiter): Waiter {
        owned++
        if (waiter.priority != ProbePriority.Active) nonActive++
        waiter.lease = OwnedProbeLease { release(waiter.priority) }
        return waiter
    }
    /** Called under lock; resume outside it so a dispatched/unconfined client never runs under this lock. */
    private fun drain(): List<Waiter> {
        waiters.removeAll { !it.continuation.isActive }
        val grants = ArrayList<Waiter>()
        while (true) {
            val next = waiters.filter { admits(it.priority) }.minByOrNull { it.priority.ordinal } ?: break
            waiters.remove(next)
            grants.add(grant(next))
        }
        return grants
    }
    private fun release(priority: ProbePriority) {
        val grants = synchronized(lock) {
            owned--
            if (priority != ProbePriority.Active) nonActive--
            drain()
        }
        grants.forEach { it.continuation.resume(it.lease!!) }
    }
    companion object { val shared = ProbeCoordinator() }
}
