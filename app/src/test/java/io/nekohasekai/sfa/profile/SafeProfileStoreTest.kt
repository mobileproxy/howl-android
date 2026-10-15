package io.nekohasekai.sfa.profile

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SafeProfileStoreTest {
    private val files = mutableMapOf<String, String>()
    private var time = 100L
    private fun store(source: String = "source-a") = SafeProfileStore(
        File("fixture.safe"), source, { files[it.path] },
        { file, value -> files[file.path] = value },
        { if (!it.startsWith("valid-")) throw ProfileFailure(ProfileFailure.Kind.INVALID) else it },
        { ++time },
    )

    private fun working(store: SafeProfileStore): SafeProfileStore.Prepared {
        store.receive("valid-old")
        return store.prepare { error("legacy read") }.also { assertTrue(store.markApplied(it)) }
    }

    @Test fun receiveDoesNotReplaceAppliedBackup() {
        val store = store()
        working(store)
        val appliedAt = store.summary().appliedAt
        store.receive("valid-new")
        assertTrue(store.summary().pending)
        assertEquals(appliedAt, store.summary().appliedAt)
        assertEquals("valid-old", store.backupOnly().content)
        assertEquals("valid-new", store.prepare { error("legacy") }.content)
    }

    @Test fun invalidReceivePreservesStateAndDates() {
        val store = store()
        working(store)
        val before = files.toMap()
        assertThrows(ProfileFailure::class.java) { store.receive("<html>secret</html>") }
        assertEquals(before, files)
    }

    @Test fun promotionRequiresMatchingCandidate() {
        val store = store()
        working(store)
        store.receive("valid-new")
        val stale = store.prepare { error("legacy") }
        store.receive("valid-newer")
        assertFalse(store.markApplied(stale))
        assertEquals("valid-old", store.backupOnly().content)
    }

    @Test fun unchangedAppliedProfileKeepsAppliedTime() {
        val store = store()
        val applied = working(store)
        val stamp = store.summary().appliedAt
        assertTrue(store.markApplied(applied))
        assertEquals(stamp, store.summary().appliedAt)
    }

    @Test fun differentSourceCannotUseOldFileOrBackup() {
        working(store())
        val other = store("source-b")
        assertThrows(ProfileFailure::class.java) { other.prepare { "valid-old" } }
        assertThrows(ProfileFailure::class.java) { other.backupOnly() }
        other.receive("valid-other")
        assertEquals(0L, other.summary().appliedAt)
    }

    @Test fun confirmedEmptyBlocksCandidateAndBackup() {
        val store = store()
        working(store)
        store.blockEmpty()
        assertTrue(store.summary().blocked)
        assertThrows(ProfileFailure::class.java) { store.prepare { "valid-old" } }
        assertThrows(ProfileFailure::class.java) { store.backupOnly() }
        store.receive("valid-returned")
        assertEquals("valid-returned", store.prepare { error("legacy") }.content)
        // Expired backup must not be resurrected after the subscription returns.
        assertThrows(ProfileFailure::class.java) { store.backupOnly() }
    }

    @Test fun legacyFileBecomesCandidateNotWorkingBackup() {
        val store = store()
        val prepared = store.prepare { "valid-migrated" }
        assertFalse(prepared.backup)
        assertEquals(0L, store.summary().appliedAt)
        assertThrows(ProfileFailure::class.java) { store.backupOnly() }
        assertTrue(store.markApplied(prepared))
    }

    @Test fun corruptCandidateFallsBackToVerifiedApplied() {
        val store = store()
        working(store)
        store.receive("valid-new")
        files["fixture.safe"] = files.getValue("fixture.safe").replace("valid-new", "broken")
        val prepared = store.prepare { error("legacy") }
        assertTrue(prepared.backup)
        assertEquals("valid-old", prepared.content)
    }

    @Test fun damagedEnvelopeNeverUsesUnboundLegacy() {
        working(store())
        files["fixture.safe"] = "broken"
        assertThrows(ProfileFailure::class.java) { store().prepare { "valid-old" } }
    }

    @Test fun persistenceFailureDoesNotPromoteOrChangeWorkingState() {
        val good = store()
        working(good)
        val before = files.toMap()
        val failing = SafeProfileStore(File("fixture.safe"), "source-a", { files[it.path] },
            { _, _ -> throw ProfileFailure(ProfileFailure.Kind.STORAGE) }, { it })
        assertThrows(ProfileFailure::class.java) { failing.receive("valid-new") }
        assertEquals(before, files)
    }
}
