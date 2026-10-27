package io.nekohasekai.sfa.health

import org.junit.Assert.*
import org.junit.Test

class ProfileSelectionPreferencesTest {
    private class MemoryStorage(var raw: String? = null) : AtomicPreferenceStorage {
        var failWrite = false
        var failRead = false
        var beforeRead: (() -> Unit)? = null
        override fun read(): String? {
            beforeRead?.also { beforeRead = null }?.invoke()
            check(!failRead)
            return raw
        }
        override fun writeAtomically(value: String) {
            check(!failWrite)
            raw = value
        }
    }
    private val storage = MemoryStorage()
    private val store = ProfileSelectionPreferences(storage)
    private fun save(profile: Long = 1, selector: String = "selector", target: String = "preferred") =
        store.recordManual(profile, selector, target, stillCurrent = { true })

    @Test fun legacyEmptyStateRemainsUnknownWithoutWritingOrInferringRuntimeFallback() {
        assertNull(store.read(1, "selector"))
        assertEquals(PreferenceDataState.Empty, store.dataState())
        assertNull(storage.raw)
    }
    @Test fun acknowledgedLocalChoiceSurvivesNewStoreAndKeepsRevision() {
        assertEquals(PreferenceWriteResult.Stored, save())
        val restored = ProfileSelectionPreferences(storage).read(1, "selector")!!
        assertEquals("preferred", restored.target)
        assertEquals(1L, restored.revision)
        assertEquals(PreferenceDataState.Ready, store.dataState())
    }
    @Test fun clickingAlreadyCurrentTargetRecordsFreshManualIntent() {
        save()
        assertEquals(PreferenceWriteResult.Stored, save())
        assertEquals(2L, store.read(1, "selector")!!.revision)
    }
    @Test fun profilesAndSelectorsHaveIndependentPreferences() {
        save(); save(2, target = "other-profile"); save(selector = "other-selector", target = "other-group")
        assertEquals("preferred", store.read(1, "selector")!!.target)
        assertEquals("other-profile", store.read(2, "selector")!!.target)
        assertEquals("other-group", store.read(1, "other-selector")!!.target)
    }
    @Test fun failedOrStaleAcknowledgmentNeverOverwritesSavedChoice() {
        save(); val old = storage.raw
        val failed = store.beginManual(1, "selector", "failed")!!
        assertEquals(PreferenceWriteResult.Rejected, store.acknowledgeManual(failed, false) { true })
        assertEquals(PreferenceWriteResult.Rejected,
            store.recordManual(1, "selector", "stale", stillCurrent = { false }))
        assertEquals(old, storage.raw)
    }
    @Test fun remoteChoiceDoesNotOverwriteOrCancelPendingLocalIntent() {
        val local = store.beginManual(1, "selector", "local")!!
        assertNull(store.beginManual(1, "selector", "remote", local = false))
        assertEquals(PreferenceWriteResult.Rejected,
            store.recordManual(1, "selector", "remote", local = false, stillCurrent = { true }))
        assertEquals(PreferenceWriteResult.Stored, store.acknowledgeManual(local, true) { true })
        assertEquals("local", store.read(1, "selector")!!.target)
    }
    @Test fun laterLocalIntentCancelsOlderAsyncAcknowledgmentEvenWhenLaterNativeChoiceFails() {
        save(); val old = storage.raw
        val first = store.beginManual(1, "selector", "slow")!!
        val second = store.beginManual(1, "selector", "newer")!!
        assertEquals(PreferenceWriteResult.Rejected, store.acknowledgeManual(second, false) { true })
        assertEquals(PreferenceWriteResult.Rejected, store.acknowledgeManual(first, true) { true })
        assertEquals(old, storage.raw)
    }
    @Test fun intentCannotBeAcknowledgedTwiceOrByAnotherStore() {
        val intent = store.beginManual(1, "selector", "preferred")!!
        assertEquals(PreferenceWriteResult.Rejected,
            ProfileSelectionPreferences(storage).acknowledgeManual(intent, true) { true })
        assertEquals(PreferenceWriteResult.Stored, store.acknowledgeManual(intent, true) { true })
        val raw = storage.raw
        assertEquals(PreferenceWriteResult.Rejected, store.acknowledgeManual(intent, true) { true })
        assertEquals(raw, storage.raw)
    }
    @Test fun manualGuardIsRecheckedAfterStorageReadBeforeAtomicWrite() {
        save(); val raw = storage.raw; var current = true
        val intent = store.beginManual(1, "selector", "stale")!!
        storage.beforeRead = { current = false }
        assertEquals(PreferenceWriteResult.Rejected, store.acknowledgeManual(intent, true) { current })
        assertEquals(raw, storage.raw)
    }
    @Test fun newerIntentDuringReadPreventsAnOlderWrite() {
        save(); val raw = storage.raw
        val intent = store.beginManual(1, "selector", "slow")!!
        storage.beforeRead = { store.beginManual(1, "selector", "newer") }
        assertEquals(PreferenceWriteResult.Rejected, store.acknowledgeManual(intent, true) { true })
        assertEquals(raw, storage.raw)
    }
    @Test fun invalidProfileSelectorAndTargetAreRejectedWithoutWriting() {
        listOf("", "   ", "a\ncontrol", "x".repeat(257)).forEach { invalid ->
            assertEquals(PreferenceWriteResult.Rejected, save(target = invalid))
            assertEquals(PreferenceWriteResult.Rejected, save(selector = invalid))
        }
        assertEquals(PreferenceWriteResult.Rejected, save(0))
        assertEquals(PreferenceWriteResult.Rejected, save(-1))
        assertNull(storage.raw)
    }
    @Test fun unicodeAndQuotedTargetsRoundTripWithoutNormalization() {
        save(selector = "выбор \"узла\"", target = "выход \\ 東京")
        assertEquals("выход \\ 東京", store.read(1, "выбор \"узла\"")!!.target)
    }
    @Test fun corruptAndUnsupportedVersionsFailClosedWithoutOverwritingRawData() {
        listOf("broken", "{}", "[]", "{\"version\":2,\"revision\":0,\"entries\":[]}",
            "{\"version\":1,\"revision\":1,\"entries\":[{\"profile\":1,\"selector\":\"selector\",\"target\":\"\",\"revision\":1}]}")
            .forEach { raw ->
                storage.raw = raw
                assertNull(store.read(1, "selector"))
                assertEquals(PreferenceWriteResult.ReadOnly, save())
                assertEquals(PreferenceWriteResult.ReadOnly, store.removeProfile(1, deletionSucceeded = true))
                assertEquals(raw, storage.raw)
            }
        storage.raw = "{\"version\":2}"
        assertEquals(PreferenceDataState.UnsupportedVersion, store.dataState())
    }
    @Test fun duplicateEntriesAndInvalidRevisionsFailClosed() {
        save(); val raw = storage.raw!!
        val one = "{\"profile\":1,\"selector\":\"selector\",\"target\":\"preferred\",\"revision\":1}"
        listOf("{\"version\":1,\"revision\":1,\"entries\":[$one,$one]}",
            "{\"version\":1,\"revision\":0,\"entries\":[$one]}",
            raw.replace("\"version\":1", "\"version\":1.5"))
            .forEach { bad -> storage.raw = bad; assertEquals(PreferenceWriteResult.ReadOnly, save()); assertEquals(bad, storage.raw) }
    }
    @Test fun entryLimitRejectsNewKeysButStillAllowsExistingChoiceUpdate() {
        for (profile in 1L..128L) assertEquals(PreferenceWriteResult.Stored, save(profile))
        val raw = storage.raw
        assertEquals(PreferenceWriteResult.Rejected, save(129))
        assertEquals(raw, storage.raw)
        assertEquals(PreferenceWriteResult.Stored, save(1, target = "new-choice"))
        assertEquals("new-choice", store.read(1, "selector")!!.target)
    }
    @Test fun oversizedRawDataIsPreservedAndNeverParsedIntoWritableState() {
        storage.raw = " ".repeat(65_537)
        assertEquals(PreferenceWriteResult.ReadOnly, save())
        assertEquals(65_537, storage.raw!!.length)
    }
    @Test fun failedAtomicWritePreservesPriorValueAndRevision() {
        save(); val raw = storage.raw; storage.failWrite = true
        assertEquals(PreferenceWriteResult.WriteFailed, save(target = "new"))
        assertEquals(raw, storage.raw)
        assertEquals(1L, store.read(1, "selector")!!.revision)
    }
    @Test fun unavailableStorageIsUnknownAndDoesNotAttemptAWrite() {
        save(); val raw = storage.raw; storage.failRead = true
        assertNull(store.read(1, "selector"))
        assertEquals(PreferenceDataState.Unavailable, store.dataState())
        assertEquals(PreferenceWriteResult.ReadOnly, save(target = "new"))
        assertEquals(raw, storage.raw)
    }
    @Test fun onlySuccessfulDeletionRemovesThatProfilesEntries() {
        save(); save(selector = "other", target = "other"); save(2, target = "second")
        val raw = storage.raw
        assertEquals(PreferenceWriteResult.Rejected, store.removeProfile(1, deletionSucceeded = false))
        assertEquals(raw, storage.raw)
        assertEquals(PreferenceWriteResult.Stored, store.removeProfile(1, deletionSucceeded = true))
        assertNull(store.read(1, "selector")); assertNull(store.read(1, "other"))
        assertEquals("second", store.read(2, "selector")!!.target)
        assertEquals(PreferenceWriteResult.Unchanged, store.removeProfile(1, deletionSucceeded = true))
    }
    @Test fun deletionInvalidatesPendingManualChoiceForDeletedProfile() {
        save(); val intent = store.beginManual(1, "selector", "late")!!
        store.removeProfile(1, deletionSucceeded = true)
        assertEquals(PreferenceWriteResult.Rejected, store.acknowledgeManual(intent, true) { true })
        assertNull(store.read(1, "selector"))
    }
    @Test fun rejectedLateConvenienceWriteCannotInvalidateNewerPendingLocalIntent() {
        val newer = store.beginManual(1, "selector", "newer")!!
        assertEquals(PreferenceWriteResult.Rejected,
            store.recordManual(1, "selector", "stale", stillCurrent = { false }))
        assertEquals(PreferenceWriteResult.Stored, store.acknowledgeManual(newer, true) { true })
        assertEquals("newer", store.read(1, "selector")!!.target)
    }
    @Test fun failedNativeAcknowledgmentConsumesItsIntent() {
        val failed = store.beginManual(1, "selector", "failed")!!
        assertEquals(PreferenceWriteResult.Rejected, store.acknowledgeManual(failed, false) { true })
        assertEquals(PreferenceWriteResult.Rejected, store.acknowledgeManual(failed, true) { true })
        assertNull(storage.raw)
    }
    @Test fun failedDeletionDoesNotCancelAnOtherwiseCurrentManualIntent() {
        val current = store.beginManual(1, "selector", "preferred")!!
        assertEquals(PreferenceWriteResult.Rejected, store.removeProfile(1, deletionSucceeded = false))
        assertEquals(PreferenceWriteResult.Stored, store.acknowledgeManual(current, true) { true })
    }
    @Test fun guardFailureAndThrowAreRejectionsWithNoSideEffect() {
        save(); val raw = storage.raw; var checks = 0
        val stale = store.beginManual(1, "selector", "stale")!!
        assertEquals(PreferenceWriteResult.Rejected, store.acknowledgeManual(stale, true) { ++checks == 1 })
        assertEquals(raw, storage.raw)
        assertEquals(PreferenceWriteResult.Rejected,
            store.recordManual(1, "selector", "throws", stillCurrent = { error("boundary") }))
        assertEquals(raw, storage.raw)
    }
    @Test fun trailingGarbageAfterValidJsonIsCorruptAndMustNotBeOverwritten() {
        save(); val corrupt = storage.raw + "trailing"; storage.raw = corrupt
        assertNull(store.read(1, "selector"))
        assertEquals(PreferenceWriteResult.ReadOnly, save())
        assertEquals(corrupt, storage.raw)
    }
    @Test fun revisionOverflowFailsClosedInsteadOfReusingAnOlderRevision() {
        storage.raw = "{\"version\":1,\"revision\":9223372036854775807,\"entries\":[]}"
        val raw = storage.raw
        assertEquals(PreferenceWriteResult.ReadOnly, save())
        assertEquals(raw, storage.raw)
    }
    @Test fun encodedSizeLimitPreservesPreviousMapInsteadOfPartiallyEvictingEntries() {
        val selector = "s".repeat(256); val target = "t".repeat(256)
        var rejected = false
        for (profile in 1L..128L) {
            val raw = storage.raw
            if (save(profile, selector, target) == PreferenceWriteResult.Rejected) {
                assertEquals(raw, storage.raw)
                assertEquals(target, store.read(1, selector)!!.target)
                rejected = true
                break
            }
        }
        assertTrue("encoded document must remain bounded", rejected)
        assertTrue(storage.raw!!.length <= 65_536)
    }
}
