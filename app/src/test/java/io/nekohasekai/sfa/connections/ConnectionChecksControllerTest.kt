package io.nekohasekai.sfa.connections

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionChecksControllerTest {
    private suspend fun waitFor(predicate: () -> Boolean) { withTimeout(5000) { while (!predicate()) delay(1) } }
    @Test fun controllerCompletesWithSnapshotAndStableOrdering() = runBlocking {
        val phases = mutableListOf<CheckPhase>()
        val controller = ConnectionChecksController(this, { { _, _ -> 180 } }, { _, phase -> phases.add(phase) })
        controller.updateTargets(listOf(CheckTarget("South · VLESS", "vless"), CheckTarget("North · TUIC", "tuic"), CheckTarget("auto", "urltest")), "profile")
        controller.start(); waitFor { controller.phase == CheckPhase.Done }
        assertEquals(listOf("North · TUIC", "South · VLESS"), controller.snapshot.results.keys.toList())
        assertEquals(2, controller.snapshot.completed); assertEquals(2, controller.snapshot.allWorking)
        assertTrue(CheckPhase.Running in phases)
    }
    @Test fun stopPreservesReadyResultAndRetryOnlyTouchesUnfinished() = runBlocking {
        var factories = 0; val calls = mutableListOf<String>(); val waiting = CompletableDeferred<Unit>()
        val controller = ConnectionChecksController(this, {
            factories++; val round = factories;
            { tag, _ -> calls.add(tag); if (tag == "b" && round == 1) { waiting.complete(Unit); awaitCancellation() }; 180 }
        }, { _, _ -> }, retryPauseMs = 0)
        controller.updateTargets(listOf(CheckTarget("a", "vless"), CheckTarget("b", "hysteria2")), 1)
        controller.start(); withTimeout(5000) { waiting.await() }; controller.stop()
        val time = controller.snapshot.results.getValue("a").checkedAt
        assertEquals(CheckState.Working, controller.snapshot.results.getValue("a").state)
        assertEquals(CheckState.Cancelled, controller.snapshot.results.getValue("b").state)
        delay(10); controller.start(true); waitFor { controller.phase == CheckPhase.Done }
        assertEquals(1, calls.count { it == "a" }); assertEquals(2, calls.count { it == "b" })
        assertEquals(time, controller.snapshot.results.getValue("a").checkedAt)
    }
    @Test fun networkChangeInvalidatesEvenCompletedResults() = runBlocking {
        val controller = ConnectionChecksController(this, { { _, _ -> 180 } }, { _, _ -> })
        controller.updateTargets(listOf(CheckTarget("a", "vless")), 1)
        controller.start(); waitFor { controller.phase == CheckPhase.Done }
        controller.invalidate(networkChanged = true)
        assertEquals(CheckPhase.NetworkChanged, controller.phase); assertTrue(controller.snapshot.results.isEmpty())
    }
    @Test fun profileOrTopologyChangeDiscardsOldCallbacks() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val controller = ConnectionChecksController(this, { { _, _ -> entered.complete(Unit); awaitCancellation() } }, { _, _ -> })
        controller.updateTargets(listOf(CheckTarget("a", "vless")), "first")
        controller.start(); withTimeout(5000) { entered.await() }
        controller.updateTargets(listOf(CheckTarget("a", "vless")), "second")
        yield(); assertEquals(CheckPhase.Idle, controller.phase); assertTrue(controller.snapshot.results.isEmpty())
    }
    @Test fun manualDiagnosticsLeasePreventsCompetingProbes() = runBlocking {
        val lease = ManualProbeGate.acquire()!!
        try {
            var called = false
            val controller = ConnectionChecksController(this, { called = true; { _, _ -> 1 } }, { _, _ -> })
            controller.updateTargets(listOf(CheckTarget("a", "vless")), 1); controller.start()
            assertEquals(CheckPhase.Busy, controller.phase); assertFalse(called)
        } finally { lease.close(); lease.close() }
        val next = ManualProbeGate.acquire(); assertNotNull(next); next!!.close()
    }
    @Test fun cancelledOwnerScopeDoesNotLeakManualLease() = runBlocking {
        val scope = CoroutineScope(Job().also { it.cancel() })
        val controller = ConnectionChecksController(scope, { { _, _ -> 180 } }, { _, _ -> })
        controller.updateTargets(listOf(CheckTarget("a", "vless")), 1); controller.start()
        delay(10)
        val next = ManualProbeGate.acquire(); assertNotNull(next); next!!.close()
    }
    @Test fun unchangedTopologyDoesNotReplaceFreshResultsWithCoreHistory() = runBlocking {
        val controller = ConnectionChecksController(this, { { _, _ -> 180 } }, { _, _ -> })
        val targets = listOf(CheckTarget("a", "vless"))
        controller.updateTargets(targets, 1); controller.start(); waitFor { controller.phase == CheckPhase.Done }
        val result = controller.snapshot.results.getValue("a")
        controller.updateTargets(targets, 1)
        assertEquals(result, controller.snapshot.results.getValue("a")); assertEquals(CheckPhase.Done, controller.phase)
    }
}
