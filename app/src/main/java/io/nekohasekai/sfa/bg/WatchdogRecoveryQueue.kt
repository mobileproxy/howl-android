package io.nekohasekai.sfa.bg

/** Survives our own network callbacks. Monotonic time; loss of the current path bypasses the hold. */
class WatchdogNetworkHold(private val intervalMs: Long = 60_000) {
    companion object {
        fun retainCellularBackup(currentIsCellular: Boolean, currentIsDefault: Boolean, force: Boolean) =
            currentIsCellular && !currentIsDefault && !force
    }
    private var changedAt: Long? = null
    @Synchronized fun allowed(now: Long, currentPresent: Boolean): Boolean =
        !currentPresent || changedAt?.let { now - it >= intervalMs } != false
    @Synchronized fun changed(now: Long) { changedAt = now }
    @Synchronized fun reset() { changedAt = null }
}
