package io.nekohasekai.sfa.health

import org.junit.Assert.*
import org.junit.Test

class ServiceStartPolicyTest {
    @Test fun delayedGuardCannotUndoPersistedOff() {
        assertFalse(ServiceStartPolicy.admitted(SessionStartKind.GUARD_RESTART, false))
    }
    @Test fun delayedStickyCannotUndoPersistedOff() {
        assertFalse(ServiceStartPolicy.admitted(SessionStartKind.STICKY, false))
    }
    @Test fun explicitUserCanConnectAfterOff() {
        assertTrue(ServiceStartPolicy.admitted(SessionStartKind.USER, false))
    }
    @Test fun automaticRestorationNeedsThePersistedIntent() {
        SessionStartKind.entries.filter { it != SessionStartKind.USER }.forEach {
            assertTrue(ServiceStartPolicy.admitted(it, true))
        }
    }
}
