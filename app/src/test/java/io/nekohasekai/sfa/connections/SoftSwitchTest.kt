package io.nekohasekai.sfa.connections

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SoftSwitchTest {
    @Test fun repeatingTheCurrentManualChoiceSupersedesAnOlderAutomaticDecision() {
        val switch = SoftSwitch(); val automatic = switch.automatic()!!
        val manual = switch.manual(); switch.finish(manual)
        assertFalse(switch.valid(automatic)); assertNotNull(switch.automatic())
    }
    @Test fun coreReloadRejectsOldAndNewTicketsUntilItFinishes() = runTest {
        val switch = SoftSwitch(); val before = switch.manual()
        switch.pause()
        assertFalse(switch.valid(before)); assertNull(switch.automatic())
        val during = switch.manual()
        assertFalse(switch.valid(during))
        switch.resume()
        assertFalse(switch.valid(during)); assertNotNull(switch.automatic())
    }

    @Test fun nestedCoreOperationsCannotResumeSelectionTooEarly() {
        val switch = SoftSwitch(); switch.pause(); switch.pause(); switch.resume()
        assertNull(switch.automatic())
        switch.resume(); assertNotNull(switch.automatic())
    }
    @Test fun cancellationBeforeTheWorkerStartsReleasesManualPriority() = runTest {
        val switch = SoftSwitch(); val ticket = switch.manual()
        val job = launch(start = CoroutineStart.LAZY) { try { error("not started") } finally { switch.finish(ticket) } }
        switch.track(ticket, job)
        job.cancel(); job.join()
        assertNotNull(switch.automatic())
    }
    @Test fun preflightRunsBeforeSelectionAndHealthyOldFlowsAreNeverClosed() = runTest {
        val switch = SoftSwitch(); val ticket = switch.manual()
        val events = mutableListOf<String>(); var selected = "A"
        val result = switch.select(ticket, "B", "A", { selected }, probe = { events += "probe:$it"; 20 },
            commit = { events += "select:B"; selected = "B" })
        assertEquals(SwitchOutcome.Switched, result)
        assertEquals(listOf("probe:B", "select:B"), events)
        assertEquals("B", selected)
    }

    @Test fun noResponseKeepsTheCurrentRouteAndZeroMillisecondSuccessIsValid() = runTest {
        val switch = SoftSwitch(); var selected = "A"; val ticket = switch.manual()
        assertEquals(SwitchOutcome.NoResponse, switch.select(ticket, "B", "A", { selected }, probe = { null }, commit = { selected = "B" }))
        assertEquals("A", selected)
        assertEquals(SwitchOutcome.Switched, switch.select(ticket, "B", "A", { selected }, probe = { 0 }, commit = { selected = "B" }))
    }

    @Test fun sameChoiceDoesNotProbeOrCommit() = runTest {
        val switch = SoftSwitch()
        assertEquals(SwitchOutcome.Unchanged, switch.select(switch.manual(), "A", "A", { "A" }, probe = { error("probe") }, commit = { error("commit") }))
    }

    @Test fun changedChoiceDuringPreflightIsNotOverwritten() = runTest {
        val switch = SoftSwitch(); var selected = "A"
        assertEquals(SwitchOutcome.Stale, switch.select(switch.manual(), "B", "A", { selected },
            probe = { selected = "C"; 20 }, commit = { selected = "B" }))
        assertEquals("C", selected)
    }

    @Test fun newManualIntentSupersedesAnAutomaticProbeEvenForTheSameChoice() = runTest {
        val switch = SoftSwitch(); val ticket = switch.automatic()!!; var committed = false
        assertEquals(SwitchOutcome.Stale, switch.select(ticket, "B", "A", { "A" },
            probe = { switch.manual(); 20 }, commit = { committed = true }))
        assertFalse(committed)
    }

    @Test fun automaticSwitchWaitsWhileAManualChoiceIsPending() {
        val switch = SoftSwitch(); val old = switch.manual(); val latest = switch.manual()
        switch.finish(old)
        assertNull(switch.automatic())
        switch.finish(latest)
        assertNotNull(switch.automatic())
    }

    @Test fun disconnectProfileOrCoreChangeInvalidatesAPendingRequest() = runTest {
        val switch = SoftSwitch(); var committed = false
        assertEquals(SwitchOutcome.Stale, switch.select(switch.manual(), "B", "A", { "A" },
            probe = { switch.invalidate(); 20 }, commit = { committed = true }))
        assertFalse(committed)
    }

    @Test fun changedRemoteOrRemovedTargetPreventsCommit() = runTest {
        val switch = SoftSwitch(); var current = true; var committed = false
        assertEquals(SwitchOutcome.Stale, switch.select(switch.manual(), "B", "A", { "A" }, { current },
            probe = { current = false; 20 }, commit = { committed = true }))
        assertFalse(committed)
    }

    @Test fun timeoutAndCancellationDoNotChangeSelection() = runTest {
        val switch = SoftSwitch(probeBudgetMs = 100); var committed = false
        assertEquals(SwitchOutcome.NoResponse, switch.select(switch.manual(), "B", "A", { "A" },
            probe = { CompletableDeferred<Int?>().await() }, commit = { committed = true }))
        val started = CompletableDeferred<Unit>()
        val job = launch {
            switch.select(switch.manual(), "B", "A", { "A" }, probe = { started.complete(Unit); CompletableDeferred<Int?>().await() }, commit = { committed = true })
        }
        started.await(); job.cancel(); job.join()
        assertFalse(committed)
    }

    @Test fun probeFailureAndUnconfirmedCommitAreDifferentOutcomes() = runTest {
        val switch = SoftSwitch(); val ticket = switch.manual()
        assertEquals(SwitchOutcome.ProbeError, switch.select(ticket, "B", "A", { "A" }, probe = { error("private details") }, commit = {}))
        assertEquals(SwitchOutcome.CommitError, switch.select(ticket, "B", "A", { "A" }, probe = { 20 }, commit = { error("unknown acknowledgement") }))
    }
}
