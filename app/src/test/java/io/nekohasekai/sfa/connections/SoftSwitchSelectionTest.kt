package io.nekohasekai.sfa.connections

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class SoftSwitchSelectionTest {
    private class Queue : Executor {
        val work = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { work.addLast(command) }
        fun runAll() { while (work.isNotEmpty()) work.removeFirst().run() }
    }
    @Test fun selectionRunsOffTheCallerAndDisconnectsOnlyItsOwnedClient() = runTest {
        val queue = Queue(); val events = mutableListOf<String>()
        val transport = BoundedSelection(factory = { object : OwnedSelectionClient {
            override fun select(group: String, tag: String) { events += "$group:$tag" }
            override fun close() { events += "disconnect-owned" }
        } }, workers = queue, closers = Executor { it.run() })
        val command = async(start = CoroutineStart.UNDISPATCHED) { transport.select("Howl", "B") { true } }
        assertEquals(1, queue.work.size)
        assertTrue(events.isEmpty())
        queue.runAll(); command.await()
        assertEquals(listOf("Howl:B", "disconnect-owned"), events)
    }

    @Test fun cancelledQueuedCommandCannotSelectLater() = runTest {
        val queue = Queue(); var created = 0
        val transport = BoundedSelection(factory = { created++; error("not expected") }, workers = queue)
        val command = async(start = CoroutineStart.UNDISPATCHED) { transport.select("Howl", "B") { true } }
        command.cancel(); queue.runAll(); command.join()
        assertEquals(0, created)
    }

    @Test fun newSessionWhileCommandQueuesPreventsSelection() = runTest {
        val queue = Queue(); var current = true; var selected = false
        val transport = BoundedSelection(factory = { object : OwnedSelectionClient {
            override fun select(group: String, tag: String) { selected = true }
            override fun close() {}
        } }, workers = queue, closers = Executor { it.run() })
        val command = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { transport.select("Howl", "B") { current } }
        }
        current = false; queue.runAll(); command.await()
        assertFalse(selected)
    }

    @Test fun queuedNativeCallHasAFiniteDeadlineAndCannotCommitAfterwards() = runTest {
        val queue = Queue(); var selected = false
        val transport = BoundedSelection(factory = { object : OwnedSelectionClient {
            override fun select(group: String, tag: String) { selected = true }
            override fun close() {}
        } }, workers = queue, timeoutMs = 100)
        val failure = runCatching { transport.select("Howl", "B") { true } }.exceptionOrNull()
        assertNotNull(failure)
        queue.runAll()
        assertFalse(selected)
    }
}
