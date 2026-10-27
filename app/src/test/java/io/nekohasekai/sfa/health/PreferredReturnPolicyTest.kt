package io.nekohasekai.sfa.health

import org.junit.Assert.*
import org.junit.Test

class PreferredReturnPolicyTest {
    private val context = PreferredReturnContext(Any(), Any(), 1, "selector", 1, Any(), 0, 1, "preferred", eligiblePreferred = true)
    private val policy = PreferredReturnPolicy()
    private fun sample(at: Long) = PreferredReturnObservation(PreferredReturnFinding.Success,
        PreferredReturnFinding.Success, at, at, manualIntentCurrent = true)
    private fun observe(at: Long, scope: PreferredReturnContext = context, value: PreferredReturnObservation? = sample(at)) =
        policy.observe(scope, value, at)
    private fun prime() { for (at in 0L..480_000L step 120_000L) assertFalse(observe(at).eligible) }

    @Test fun defaultReturnRequiresTenMinutesOfContinuousFreshSuccesses() {
        prime()
        val decision = observe(600_000)
        assertTrue(decision.eligible)
        assertEquals(0L, decision.continuousSinceMs)
        assertEquals(6, decision.successfulSamples)
    }
    @Test fun tenMinutesSinceHealingWithoutPreferredObservationsCannotAuthorizeReturn() {
        observe(0)
        val decision = observe(600_000)
        assertFalse(decision.eligible)
        assertEquals(600_000L, decision.continuousSinceMs)
        assertEquals(1, decision.successfulSamples)
    }
    @Test fun minimumSampleCountAlsoAppliesWhenDurationIsShorter() {
        val short = PreferredReturnPolicy(stabilityMs = 10, minimumSamples = 3)
        assertFalse(short.observe(context, sample(0), 0).eligible)
        assertFalse(short.observe(context, sample(10), 10).eligible)
        assertTrue(short.observe(context, sample(11), 11).eligible)
    }
    @Test fun unavailableFailedAndMissingPreferredResultResetContinuousWindow() {
        listOf(PreferredReturnFinding.Failed, PreferredReturnFinding.Unavailable, PreferredReturnFinding.Missing).forEach { finding ->
            policy.reset(); prime()
            assertEquals(0, observe(500_000, value = sample(500_000).copy(preferred = finding)).successfulSamples)
            assertFalse(observe(600_000).eligible)
        }
    }
    @Test fun absentObservationResetsInsteadOfTreatingSilenceAsSuccess() {
        prime(); assertEquals(0, observe(500_000, value = null).successfulSamples)
        assertFalse(observe(600_000).eligible)
    }
    @Test fun failedUnavailableOrMissingPhysicalConfirmationResets() {
        listOf(PreferredReturnFinding.Failed, PreferredReturnFinding.Unavailable, PreferredReturnFinding.Missing).forEach { finding ->
            policy.reset(); prime()
            assertFalse(observe(600_000, value = sample(600_000).copy(physical = finding)).eligible)
            assertFalse(observe(600_001).eligible)
        }
    }
    @Test fun stalePreferredOrPhysicalEvidenceCannotAuthorizeReturn() {
        prime()
        assertFalse(observe(600_000, value = sample(600_000).copy(preferredCheckedAtMs = 480_000)).eligible)
        primeAfterReset()
        assertFalse(observe(600_000, value = sample(600_000).copy(physicalCheckedAtMs = 480_000)).eligible)
    }
    private fun primeAfterReset() { policy.reset(); prime() }
    @Test fun immediateManualIntentGuardMustStillBeCurrent() {
        prime()
        assertFalse(observe(600_000, value = sample(600_000).copy(manualIntentCurrent = false)).eligible)
        assertFalse(observe(600_001).eligible)
    }
    @Test fun dozeGapStartsACompletelyNewWindow() {
        prime()
        val decision = observe(600_001)
        assertFalse(decision.eligible)
        assertEquals(600_001L, decision.continuousSinceMs)
    }
    @Test fun negativeAndBackwardsTimeCannotAccumulateStability() {
        prime()
        assertEquals(0, observe(479_999).successfulSamples)
        assertFalse(observe(600_000).eligible)
        assertEquals(0, observe(-1).successfulSamples)
    }
    @Test fun futureOrMissingTimestampsCannotCountAsFreshEvidence() {
        listOf(sample(600_001), sample(600_000).copy(preferredCheckedAtMs = null),
            sample(600_000).copy(physicalCheckedAtMs = null)).forEach { invalid ->
            primeAfterReset()
            assertFalse(observe(600_000, value = invalid).eligible)
        }
    }
    @Test fun duplicateSampleCannotAddCountOrBecomeEligibleAsTimePasses() {
        observe(0)
        val duplicate = observe(60_000, value = sample(0))
        assertEquals(1, duplicate.successfulSamples)
        assertFalse(duplicate.eligible)
        primeAfterReset(); assertTrue(observe(600_000).eligible)
        assertFalse(observe(600_001, value = sample(600_000)).eligible)
    }
    @Test fun olderOutOfOrderPreferredSampleResetsWindow() {
        prime()
        assertEquals(0, observe(500_000, value = sample(400_000)).successfulSamples)
        assertFalse(observe(600_000).eligible)
    }
    @Test fun serviceSessionProfileSelectorConfigNetworkManualAndPreferenceChangesReset() {
        listOf(context.copy(service = Any()), context.copy(session = Any()), context.copy(profile = 2),
            context.copy(selector = "other"), context.copy(configGeneration = 2), context.copy(network = Any()),
            context.copy(manualRevision = 1), context.copy(preferenceRevision = 2), context.copy(preferredTarget = "new"))
            .forEach { changed ->
                primeAfterReset()
                val decision = observe(600_000, changed)
                assertFalse(decision.eligible)
                assertEquals(600_000L, decision.continuousSinceMs)
                assertEquals(1, decision.successfulSamples)
            }
    }
    @Test fun offRemoteMissingNetworkAndUnknownPreferenceCannotAuthorizeAutomaticReturn() {
        listOf(context.copy(active = false), context.copy(local = false), context.copy(network = null),
            context.copy(preferenceRevision = 0), context.copy(preferredTarget = null), context.copy(profile = 0))
            .forEach { invalid -> primeAfterReset(); assertEquals(0, observe(600_000, invalid).successfulSamples) }
        assertFalse(policy.observe(null, sample(700_000), 700_000).eligible)
    }
    @Test fun directRejectBlockDnsUnknownOrMissingConfiguredTargetNeverReturnAutomatically() {
        // The config adapter supplies this typed eligibility; policy never guesses a kind from a private tag.
        listOf("direct", "reject", "block", "dns", "unknown", "missing").forEach { label ->
            primeAfterReset()
            assertEquals(0, observe(600_000, context.copy(preferredTarget = label, eligiblePreferred = false)).successfulSamples)
        }
    }
    @Test fun emergencyRuntimeSelectionIsOutsideLogicalPreferenceContext() {
        // Own soft fallback changes the running selection, not manual/preference revision.
        prime()
        assertTrue(observe(600_000, context.copy()).eligible)
    }
    @Test fun processRestartOrExplicitResetKeepsNoStabilityWindow() {
        prime(); policy.reset()
        assertFalse(observe(600_000).eligible)
        assertFalse(PreferredReturnPolicy().observe(context, sample(600_000), 600_000).eligible)
    }
    @Test fun oldFreshSuccessDeliveredLateCannotUseDeliveryTimeAsTenMinuteHistory() {
        prime()
        assertFalse(observe(600_000, value = sample(590_000)).eligible)
        assertTrue(observe(610_000).eligible)
    }
}
