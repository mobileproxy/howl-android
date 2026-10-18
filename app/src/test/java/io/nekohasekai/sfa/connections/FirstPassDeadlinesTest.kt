package io.nekohasekai.sfa.connections

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FirstPassDeadlinesTest {
    @Test fun overallDeadlineKeepsSuccessAndMarksActiveAndQueuedUnfinished() = runBlocking {
        val events = mutableListOf<CheckResult>()
        val final = withTimeout(5000) {
            ConnectionChecks.run(listOf("working") + (0..11).map { "waiting-$it" }, { tag, _ ->
                if (tag == "working") 160 else awaitCancellation()
            }, events::add, attemptDeadlineMs = 1000, totalDeadlineMs = 80)
        }
        assertEquals(CheckState.Working, final.first().state)
        assertEquals(160, final.first().delayMs)
        assertEquals(12, final.count { it.state == CheckState.NotCompleted && it.issue == CheckIssue.RunDeadline })
        assertTrue(final.filter { it.state == CheckState.NotCompleted }.all { it.checkedAt == null })
        assertFalse(events.any { it.state == CheckState.NoResponse || it.state == CheckState.Cancelled })
    }

    @Test fun firstAndRetryHaveDifferentVisibleBudgets() = runBlocking {
        val events = mutableListOf<CheckResult>()
        ConnectionChecks.run(listOf("fixture"), { _, attempt -> if (attempt == 1) awaitCancellation() else 0 },
            events::add, retryPauseMs = 0, attemptDeadlineMs = 20, retryDeadlineMs = 60, now = { 123 })
        val first = events.single { it.state == CheckState.Checking }
        val second = events.single { it.state == CheckState.Retrying }
        assertEquals(20L, first.timeoutMs); assertEquals(60L, second.timeoutMs)
        assertEquals(123L, first.startedAt); assertEquals(123L, second.startedAt)
        assertEquals(CheckState.Working, events.last().state)
        assertEquals(0, events.last().delayMs)
    }

    @Test fun snapshotSeparatesQueuesAndFirstPassFromConfirmations() {
        val session = ConnectionCheckSession()
        val generation = session.begin(listOf("a", "b", "c", "d"))
        session.apply(generation, CheckResult("a", CheckState.Working, 180, 1))
        session.apply(generation, CheckResult("b", CheckState.RetryWaiting, attempt = 1))
        session.apply(generation, CheckResult("c", CheckState.Checking, attempt = 1))
        assertEquals(2, session.snapshot.firstPassCompleted)
        assertEquals(1, session.snapshot.completed)
        assertEquals(1, session.snapshot.running); assertEquals(1, session.snapshot.queued)
        assertEquals(1, session.snapshot.waitingToRetry)
        assertFalse(session.snapshot.confirming)
        session.apply(generation, CheckResult("c", CheckState.Working, 120, 1))
        session.apply(generation, CheckResult("d", CheckState.Error, attempt = 1))
        session.apply(generation, CheckResult("b", CheckState.Retrying, attempt = 2))
        assertTrue(session.snapshot.confirming)
        assertEquals(1, session.snapshot.retryTotal); assertEquals(0, session.snapshot.retryCompleted)
        session.apply(generation, CheckResult("b", CheckState.NoResponse, attempt = 2))
        assertEquals(1, session.snapshot.retryCompleted)
        assertEquals(4, session.snapshot.completed)
    }

    @Test fun unfinishedIsRetryableAndCannotBeChangedByLateSuccess() {
        val session = ConnectionCheckSession(); val generation = session.begin(listOf("a"))
        session.apply(generation, CheckResult("a", CheckState.NotCompleted, issue = CheckIssue.RunDeadline))
        assertEquals(0, session.snapshot.completed)
        assertEquals(listOf("a"), session.snapshot.unresolved)
        assertFalse(session.apply(generation, CheckResult("a", CheckState.Working, 10)))
    }

    @Test fun clockContinuesWithoutRepliesAndFreezesOnStop() = runBlocking {
        val controller = ConnectionChecksController(this, { { _, _ -> awaitCancellation() } }, { _, _ -> }, tickMs = 20)
        controller.updateTargets(listOf(CheckTarget("fixture", "vless")), 1)
        controller.start()
        try {
            withTimeout(5000) { while (controller.snapshot.elapsedMs < 20) delay(1) }
            assertEquals(0, controller.snapshot.completed)
            assertEquals(CheckPhase.Running, controller.phase)
            assertTrue(controller.snapshot.clockAt > 0)
            controller.stop()
            val elapsed = controller.snapshot.elapsedMs
            delay(60)
            assertEquals(elapsed, controller.snapshot.elapsedMs)
            assertEquals(CheckPhase.Stopped, controller.phase)
        } finally { controller.stop(); yield() }
    }

    @Test fun controllerHasExplicitDeadlinePhaseAndRetainsWorkingRows() = runBlocking {
        val controller = ConnectionChecksController(this, { { tag, _ -> if (tag == "a") 150 else awaitCancellation() } },
            { _, _ -> }, attemptDeadlineMs = 1000, totalDeadlineMs = 80)
        controller.updateTargets(listOf(CheckTarget("a", "vless"), CheckTarget("b", "tuic")), 1)
        controller.start()
        try {
            withTimeout(5000) { while (controller.phase == CheckPhase.Running) delay(1) }
            assertEquals(CheckPhase.TimedOut, controller.phase)
            assertEquals(CheckState.Working, controller.snapshot.results.getValue("a").state)
            assertEquals(CheckState.NotCompleted, controller.snapshot.results.getValue("b").state)
            assertEquals(listOf("b"), controller.snapshot.unresolved)
        } finally { controller.stop(); yield() }
    }
}
