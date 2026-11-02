package io.nekohasekai.sfa.bg

/** Progress is independent of server health: Unknown advances the pass without blaming a node. */
class WatchdogRecoveryQueue {
    private val visited = mutableSetOf<String>()
    var pass = 0
        private set
    fun next(ordered: List<String>, limit: Int): List<String> {
        require(limit > 0)
        val pool = ordered.distinct()
        visited.retainAll(pool.toSet())
        if (pool.isNotEmpty() && pool.all { it in visited }) { visited.clear(); pass++ }
        return pool.filterNot { it in visited }.take(limit).also { visited.addAll(it) }
    }
    fun reset() { visited.clear(); pass = 0 }
}

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
