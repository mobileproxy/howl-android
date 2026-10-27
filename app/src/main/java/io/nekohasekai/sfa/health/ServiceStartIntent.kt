package io.nekohasekai.sfa.health

/** In-process fence for asynchronous startup; persisted user intent remains authoritative. */
class ServiceStartIntent {
    private var revision = 0L
    @Synchronized fun begin() = ++revision
    @Synchronized fun current(ticket: Long, userWantsRunning: Boolean) = userWantsRunning && ticket == revision
    @Synchronized fun stop() { revision++ }
}
