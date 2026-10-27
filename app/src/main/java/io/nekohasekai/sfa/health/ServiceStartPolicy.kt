package io.nekohasekai.sfa.health

/** Delayed automatic intents cannot create new user intent. */
object ServiceStartPolicy {
    fun admitted(kind: SessionStartKind, persistedDesired: Boolean): Boolean = kind == SessionStartKind.USER || persistedDesired
}
