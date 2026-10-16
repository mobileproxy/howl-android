package io.nekohasekai.sfa.connections

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Owned by one visible groups screen; reducer and callbacks run on its UI scope. */
class ConnectionChecksController(private val scope: CoroutineScope,
    private val measureFactory: () -> (suspend (String, Int) -> Int?),
    private val publish: (CheckSnapshot, CheckPhase) -> Unit,
    private val retryPauseMs: Long = 2000) {
    private val session = ConnectionCheckSession()
    private var job: Job? = null
    private var targets = emptyList<CheckTarget>()
    private var context: Any? = null
    var phase = CheckPhase.Idle
        private set
    val snapshot get() = session.snapshot
    private fun notifyState() = publish(snapshot, phase)
    fun updateTargets(next: List<CheckTarget>, context: Any) {
        val ordered = next.filter { it.checkable }.distinctBy { it.tag }.sortedBy { it.location.lowercase() }
        if (ordered == targets && context == this.context) return
        stop(); targets = ordered; this.context = context
        session.clear(); phase = CheckPhase.Idle; notifyState()
    }
    fun start(retryOnly: Boolean = false) {
        if (phase == CheckPhase.Running) return
        val lease = ManualProbeGate.acquire()
        if (lease == null) { phase = CheckPhase.Busy; notifyState(); return }
        val tags = if (retryOnly && snapshot.results.isNotEmpty()) snapshot.unresolved else targets.map { it.tag }
        val generation = session.begin(tags, reset = !retryOnly || snapshot.results.isEmpty())
        phase = CheckPhase.Running; notifyState()
        job = scope.launch {
            try {
                val measure = measureFactory()
                val final = ConnectionChecks.run(tags, measure, { result ->
                    if (session.apply(generation, result)) notifyState()
                }, retryPauseMs)
                final.forEach { session.apply(generation, it) }
                if (generation == session.generation) { phase = CheckPhase.Done; notifyState() }
            } catch (_: CancellationException) {
                // stop/invalidate already marked unfinished rows and changed the generation.
            } catch (_: Exception) {
                if (generation == session.generation) { session.cancel(); phase = CheckPhase.Failed; notifyState() }
            } finally { lease.close() }
        }
        job?.invokeOnCompletion { lease.close() }
    }
    fun stop() {
        job?.cancel(); job = null
        session.cancel()
        if (phase == CheckPhase.Running) phase = CheckPhase.Stopped
        notifyState()
    }
    fun invalidate(networkChanged: Boolean = false) {
        stop(); session.clear()
        phase = if (networkChanged) CheckPhase.NetworkChanged else CheckPhase.Idle
        notifyState()
    }
}
