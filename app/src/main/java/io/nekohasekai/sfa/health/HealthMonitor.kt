package io.nekohasekai.sfa.health

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HealthMonitor(private val scope: CoroutineScope, private val clock: () -> Long) {
    private val tracker = HealthTracker()
    private val updates = MutableStateFlow(tracker.view(clock()))
    private var expiry: Job? = null
    private var current: HealthObservation? = null
    private var epoch = 0L
    private var temporaryFallback = false
    val state = updates.asStateFlow()
    @Synchronized fun begin(context: HealthContext): HealthObservation {
        val now = clock()
        return tracker.begin(context, now).also { current = it; publish(now) }
    }
    @Synchronized fun observe(observation: HealthObservation, evidence: HealthEvidence): Boolean {
        val now = clock()
        return tracker.observe(observation, evidence, now).also { if (it) publish(now) }
    }
    @Synchronized fun recovering(observation: HealthObservation): Boolean {
        val now = clock()
        return tracker.recovering(observation, now).also { if (it) publish(now) }
    }
    @Synchronized fun view(): HealthSnapshot = tracker.view(clock()).copy(temporaryFallback = temporaryFallback)
    @Synchronized fun refresh() = publish(clock())
    @Synchronized fun profileProblem() {
        current = null
        temporaryFallback = false
        tracker.profileProblem()
        publish(clock())
    }
    @Synchronized fun setTemporaryFallback(value: Boolean) {
        temporaryFallback = value
        updates.value = tracker.view(clock()).copy(temporaryFallback = value)
    }
    @Synchronized fun stop() {
        current = null
        temporaryFallback = false
        tracker.stop()
        publish(clock())
    }
    /** One deadline timer; no periodic poll while idle or after evidence expires. */
    private fun publish(now: Long) {
        expiry?.cancel()
        expiry = null
        val token = ++epoch
        val value = tracker.view(now).copy(temporaryFallback = temporaryFallback)
        updates.value = value
        val deadlines = mutableListOf<Long>()
        if (value.checking || value.assessment.state == HealthState.Recovering) {
            current?.let { deadlines += it.startedAt + HealthTracker.OBSERVATION_MS + 1 }
        }
        if (value.assessment.cause != HealthCause.EvidenceExpired && value.assessment.state != HealthState.ProfileProblem) {
            value.checkedAt?.let { deadlines += it + HealthTracker.FRESH_FOR_MS }
        }
        val next = deadlines.filter { it > now }.minOrNull() ?: return
        expiry = scope.launch {
            delay(next - now)
            synchronized(this@HealthMonitor) {
                if (token == epoch) publish(clock())
            }
        }
    }
}
