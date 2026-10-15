package io.nekohasekai.sfa.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfileLauncherTest {
    private val candidate = SafeProfileStore.Prepared("candidate", "source", "new", false)
    private val previous = SafeProfileStore.Prepared("previous", "source", "old", true)

    @Test fun invalidCandidateDoesNotTouchCore() = runBlocking {
        var starts = 0
        var backups = 0
        try {
            ProfileLauncher.launch(candidate, "invalid", { backups++; previous }, { it },
                { error("invalid") }, { starts++ })
            fail("expected rejection")
        } catch (_: IllegalStateException) { }
        assertEquals(0, starts)
        assertEquals(0, backups)
    }

    @Test fun failedStartRestoresPreviousAppliedProfile() = runBlocking {
        val starts = mutableListOf<String>()
        val result = ProfileLauncher.launch(candidate, "candidate", { previous }, { it },
            {}, { starts.add(it); if (it == "candidate") error("start failed") })
        assertEquals(listOf("candidate", "previous"), starts)
        assertEquals(previous, result.prepared)
    }

    @Test fun cancellationDoesNotStartBackup() = runBlocking {
        var backups = 0
        try {
            ProfileLauncher.launch(candidate, "candidate", { backups++; previous }, { it },
                {}, { throw CancellationException() })
            fail("expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(0, backups)
    }

    @Test fun successfulStartDoesNotReadBackup() = runBlocking {
        val result = ProfileLauncher.launch(candidate, "candidate", { error("backup read") }, { it }, {}, {})
        assertEquals(candidate, result.prepared)
    }

    @Test fun failedBackupIsNotReportedAsApplied() = runBlocking {
        var succeeded = false
        try {
            ProfileLauncher.launch(candidate, "candidate", { previous }, { it }, {}, { error("failed") })
            succeeded = true
        } catch (_: IllegalStateException) { }
        assertFalse(succeeded)
    }
}
