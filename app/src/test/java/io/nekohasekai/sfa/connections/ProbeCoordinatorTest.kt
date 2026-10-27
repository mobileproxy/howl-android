package io.nekohasekai.sfa.connections

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeCoordinatorTest {
    @Test fun sixManualOwnersLeaveTwoRealSlotsForActiveRequests() = runTest {
        val coordinator = ProbeCoordinator()
        val manual = List(6) { coordinator.acquire(ProbePriority.ManualScan) }
        val blocked = async { coordinator.acquire(ProbePriority.Recovery) }; runCurrent()
        assertFalse(blocked.isCompleted)
        val active = List(2) { coordinator.acquire(ProbePriority.Active) }
        val ninth = async { coordinator.acquire(ProbePriority.Active) }; runCurrent()
        assertFalse(ninth.isCompleted)
        active[0].close(); runCurrent(); assertTrue(ninth.isCompleted)
        assertFalse(blocked.isCompleted)
        ninth.await().close(); active[1].close(); manual.forEach { it.close() }
        runCurrent(); blocked.await().close()
    }
    @Test fun recoveryManualAndBackgroundCannotBorrowActiveReservation() = runTest {
        val coordinator = ProbeCoordinator()
        val owners = List(6) { coordinator.acquire(ProbePriority.Background) }
        val waiting = listOf(ProbePriority.Recovery, ProbePriority.ManualScan, ProbePriority.Background).map { priority ->
            async { coordinator.acquire(priority) }
        }
        runCurrent(); waiting.forEach { assertFalse(it.isCompleted) }
        waiting.forEach { it.cancelAndJoin() }; owners.forEach { it.close() }
    }
    @Test fun waitingIsPriorityOrderedWithFifoWithinOnePriority() = runTest {
        val coordinator = ProbeCoordinator()
        val owners = List(8) { coordinator.acquire(ProbePriority.Active) }
        val order = mutableListOf<String>()
        val background = async { coordinator.acquire(ProbePriority.Background).also { order.add("background") } }
        val manual1 = async { coordinator.acquire(ProbePriority.ManualScan).also { order.add("manual1") } }
        val recovery = async { coordinator.acquire(ProbePriority.Recovery).also { order.add("recovery") } }
        val manual2 = async { coordinator.acquire(ProbePriority.ManualScan).also { order.add("manual2") } }
        val active = async { coordinator.acquire(ProbePriority.Active).also { order.add("active") } }
        runCurrent()
        owners[0].close(); runCurrent(); assertEquals(listOf("active"), order)
        active.await().close(); runCurrent(); assertEquals(listOf("active", "recovery"), order)
        recovery.await().close(); runCurrent(); assertEquals(listOf("active", "recovery", "manual1"), order)
        manual1.await().close(); runCurrent(); assertEquals(listOf("active", "recovery", "manual1", "manual2"), order)
        manual2.await().close(); runCurrent(); assertEquals(listOf("active", "recovery", "manual1", "manual2", "background"), order)
        background.await().close(); owners.drop(1).forEach { it.close() }
    }
    @Test fun cancelledWaiterNeverReceivesOrLeaksARealSlot() = runTest {
        val coordinator = ProbeCoordinator()
        val owners = List(8) { coordinator.acquire(ProbePriority.Active) }
        val cancelled = async { coordinator.acquire(ProbePriority.Active) }; runCurrent()
        cancelled.cancelAndJoin(); owners[0].close()
        val replacement = coordinator.acquire(ProbePriority.Active)
        replacement.close(); owners.drop(1).forEach { it.close() }
    }
    @Test fun cancellationAfterGrantBeforeDispatchReturnsOwnership() = runTest {
        val coordinator = ProbeCoordinator()
        val owners = List(8) { coordinator.acquire(ProbePriority.Active) }
        val waiting = async { coordinator.acquire(ProbePriority.Active) }; runCurrent()
        owners[0].close(); waiting.cancelAndJoin()
        val replacement = coordinator.acquire(ProbePriority.Active)
        replacement.close(); owners.drop(1).forEach { it.close() }
    }
    @Test fun closingOneLeaseTwiceCannotCreateAdditionalCapacity() = runTest {
        val coordinator = ProbeCoordinator()
        val owners = List(8) { coordinator.acquire(ProbePriority.Active) }
        owners[0].close(); owners[0].close()
        val replacement = coordinator.acquire(ProbePriority.Active)
        val excess = async { coordinator.acquire(ProbePriority.Active) }; runCurrent()
        assertFalse(excess.isCompleted)
        excess.cancelAndJoin(); replacement.close(); owners.drop(1).forEach { it.close() }
    }
    @Test fun boundedWaitersRejectOverflowWithoutStealingCapacity() = runTest {
        val coordinator = ProbeCoordinator(maximumWaiters = 2)
        val owners = List(8) { coordinator.acquire(ProbePriority.Active) }
        val first = async { coordinator.acquire(ProbePriority.Active) }
        val second = async { coordinator.acquire(ProbePriority.Active) }; runCurrent()
        assertTrue(runCatching { coordinator.acquire(ProbePriority.Active) }.exceptionOrNull() is ProbeAdmissionBusyException)
        first.cancelAndJoin(); second.cancelAndJoin(); owners.forEach { it.close() }
        val next = coordinator.acquire(ProbePriority.Active); next.close()
    }
    @Test fun fullBackgroundWaitingListCannotBlockFreeReservedActiveCapacity() = runTest {
        val coordinator = ProbeCoordinator(maximumWaiters = 1)
        val manual = List(6) { coordinator.acquire(ProbePriority.ManualScan) }
        val background = async { coordinator.acquire(ProbePriority.Background) }; runCurrent()
        val active = runCatching { coordinator.acquire(ProbePriority.Active) }.getOrNull()
        try { assertNotNull("Reserved real capacity must remain usable under waiter pressure", active) }
        finally { active?.close(); background.cancelAndJoin(); manual.forEach { it.close() } }
    }
}
