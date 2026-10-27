package io.nekohasekai.sfa.health

/** Pure reducer; caller uses one monotonic clock (elapsedRealtime on Android). */
class HealthTracker {
    companion object {
        const val FRESH_FOR_MS = 120_000L
        const val OBSERVATION_MS = 30_000L
    }
    private var revision = 0L
    private var current: HealthObservation? = null
    private var snapshot = HealthSnapshot(assessment = HealthAssessment(HealthState.Off))

    @Synchronized fun begin(context: HealthContext, now: Long): HealthObservation {
        val previous = view(now)
        val observation = HealthObservation(context, ++revision, now)
        current = observation
        val keep = previous.context == context && previous.assessment.state == HealthState.Available
        snapshot = if (keep) previous.copy(checking = true)
            else HealthSnapshot(context, HealthAssessment(HealthState.Checking), checking = true)
        return observation
    }

    private fun valid(observation: HealthObservation, now: Long): Boolean =
        current == observation && now >= observation.startedAt && now - observation.startedAt <= OBSERVATION_MS

    @Synchronized fun observe(observation: HealthObservation, evidence: HealthEvidence, now: Long): Boolean {
        if (!valid(observation, now)) return false
        snapshot = HealthSnapshot(observation.context, ConnectionHealth.classify(evidence), now)
        return true
    }

    @Synchronized fun recovering(observation: HealthObservation, now: Long): Boolean {
        if (!valid(observation, now)) return false
        snapshot = snapshot.copy(assessment = HealthAssessment(HealthState.Recovering, HealthCause.SelectedRequestsFailed), checking = false)
        return true
    }

    @Synchronized fun view(now: Long): HealthSnapshot {
        val current = current ?: return snapshot
        val checked = snapshot.checkedAt
        if ((snapshot.checking || snapshot.assessment.state == HealthState.Recovering) &&
            (now < current.startedAt || now - current.startedAt > OBSERVATION_MS)) {
            return snapshot.copy(assessment = HealthAssessment(HealthState.CheckUnavailable, HealthCause.EvidenceExpired), checking = false)
        }
        if (checked != null && snapshot.assessment.state != HealthState.ProfileProblem &&
            (now < checked || now - checked >= FRESH_FOR_MS)) {
            return snapshot.copy(assessment = HealthAssessment(HealthState.Unverified, HealthCause.EvidenceExpired), checking = false)
        }
        return snapshot
    }

    @Synchronized fun stop() {
        revision++
        current = null
        snapshot = HealthSnapshot(assessment = HealthAssessment(HealthState.Off))
    }
    @Synchronized fun profileProblem() {
        revision++
        current = null
        snapshot = HealthSnapshot(assessment = HealthAssessment(HealthState.ProfileProblem, HealthCause.ProfileInvalid))
    }
}
