package io.nekohasekai.sfa.health

import org.junit.Assert.*
import org.junit.Test

class ServiceStartIntentTest {
    @Test fun explicitStopWinsOverAnUnfinishedStartup() {
        val intent = ServiceStartIntent()
        val first = intent.begin()
        intent.stop()
        assertFalse(intent.current(first, true))
    }
    @Test fun persistedOffRejectsStickyStartup() {
        val intent = ServiceStartIntent()
        assertFalse(intent.current(intent.begin(), false))
    }
    @Test fun newStartNeverRevalidatesOldStartup() {
        val intent = ServiceStartIntent()
        val old = intent.begin()
        intent.stop()
        val new = intent.begin()
        assertFalse(intent.current(old, true))
        assertTrue(intent.current(new, true))
    }
}
