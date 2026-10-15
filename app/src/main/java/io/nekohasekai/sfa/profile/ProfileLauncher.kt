package io.nekohasekai.sfa.profile

import kotlinx.coroutines.CancellationException

/** Native start is injectable so rollback can be verified without starting a VPN. */
object ProfileLauncher {
    data class Accepted(val prepared: SafeProfileStore.Prepared, val assembled: String)

    suspend fun launch(
        prepared: SafeProfileStore.Prepared,
        assembled: String,
        backup: suspend () -> SafeProfileStore.Prepared,
        assemble: (String) -> String,
        check: (String) -> Unit,
        start: (String) -> Unit,
    ): Accepted {
        // Rejection here leaves the currently running core untouched.
        check(assembled)
        try {
            start(assembled)
            return Accepted(prepared, assembled)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val previous = backup()
            if (previous.hash == prepared.hash) throw e
            val restored = assemble(previous.content)
            check(restored)
            start(restored)
            return Accepted(previous, restored)
        }
    }
}
