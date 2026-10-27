package io.nekohasekai.sfa.health

enum class PreferredReturnFinding { Success, Failed, Unavailable, Missing }

/** Own emergency runtime selections are intentionally absent: they are not new manual intent. */
data class PreferredReturnContext(val service: Any, val session: Any, val profile: Long, val selector: String,
    val configGeneration: Long, val network: Any?, val manualRevision: Long, val preferenceRevision: Long,
    val preferredTarget: String?, val eligiblePreferred: Boolean = false, val active: Boolean = true, val local: Boolean = true)
data class PreferredReturnObservation(val preferred: PreferredReturnFinding = PreferredReturnFinding.Missing,
    val physical: PreferredReturnFinding = PreferredReturnFinding.Missing, val preferredCheckedAtMs: Long? = null,
    val physicalCheckedAtMs: Long? = null, val manualIntentCurrent: Boolean = false)
data class PreferredReturnDecision(val eligible: Boolean = false, val continuousSinceMs: Long? = null,
    val successfulSamples: Int = 0)

/**
 * Decision only: the adapter still MUST apply the original SoftSwitch ticket,
 * current-selection, current preference/manual revision and fresh probe guards.
 * No timer since healing, automatic selection, persistent stability, or node inference.
 */
class PreferredReturnPolicy(private val stabilityMs: Long = 600_000, private val minimumSamples: Int = 3,
    private val maximumGapMs: Long = 120_000, private val freshnessMs: Long = 120_000) {
    init { require(stabilityMs >= 0 && minimumSamples >= 3 && maximumGapMs > 0 && freshnessMs > 0) }
    private var scope: PreferredReturnContext? = null
    private var lastNow: Long? = null
    private var startedAt: Long? = null
    private var lastPreferredAt: Long? = null
    private var count = 0

    @Synchronized fun observe(context: PreferredReturnContext?, observation: PreferredReturnObservation?, nowMs: Long): PreferredReturnDecision {
        if (nowMs < 0 || lastNow?.let { nowMs < it } == true) {
            reset()
            return PreferredReturnDecision()
        }
        val nowGap = lastNow?.let { nowMs - it > maximumGapMs } == true
        lastNow = nowMs
        val preferredAt = observation?.preferredCheckedAtMs
        val physicalAt = observation?.physicalCheckedAtMs
        fun fresh(at: Long?) = at != null && at >= 0 && at <= nowMs && nowMs - at < freshnessMs
        if (context == null || !context.active || !context.local || context.network == null || context.profile <= 0 ||
            context.configGeneration < 0 || context.manualRevision < 0 || context.preferenceRevision <= 0 ||
            context.selector.isBlank() || context.preferredTarget.isNullOrBlank() || !context.eligiblePreferred ||
            observation == null || !observation.manualIntentCurrent ||
            observation.preferred != PreferredReturnFinding.Success || observation.physical != PreferredReturnFinding.Success ||
            !fresh(preferredAt) || !fresh(physicalAt)) {
            clearWindow()
            return PreferredReturnDecision()
        }
        // Timestamp freshness above proves these are non-null monotonic samples.
        val checkedAt = preferredAt!!
        if (scope != context || nowGap) clearWindow()
        val previous = lastPreferredAt
        if (previous != null && checkedAt < previous) {
            clearWindow()
            return PreferredReturnDecision()
        }
        if (previous != null && checkedAt == previous) return decision(false)
        if (previous != null && checkedAt - previous > maximumGapMs) clearWindow()
        scope = context
        if (startedAt == null) startedAt = checkedAt
        lastPreferredAt = checkedAt
        if (count < Int.MAX_VALUE) count++
        return decision(checkedAt - startedAt!! >= stabilityMs && count >= minimumSamples)
    }

    @Synchronized fun reset() { clearWindow(); lastNow = null }
    private fun clearWindow() { scope = null; startedAt = null; lastPreferredAt = null; count = 0 }
    private fun decision(eligible: Boolean) = PreferredReturnDecision(eligible, startedAt, count)
}
