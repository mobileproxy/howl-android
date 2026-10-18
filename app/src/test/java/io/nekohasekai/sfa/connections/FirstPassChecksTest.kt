package io.nekohasekai.sfa.connections

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
class FirstPassChecksTest {
    @Test fun freeWorkersReachTailWhileOneFirstAttemptIsStillWaiting() = runTest {
        val slow = CompletableDeferred<Int?>()
        val events = mutableListOf<CheckResult>()
        val job = async {
            ConnectionChecks.run((0..11).map { "fixture-$it" }, { tag, attempt ->
                if (tag == "fixture-0" && attempt == 1) slow.await() else 180
            }, events::add, retryPauseMs = 0)
        }
        runCurrent()
        assertFalse(job.isCompleted)
        assertEquals(11, events.count { it.state == CheckState.Working })
        assertTrue(events.any { it.tag == "fixture-11" && it.state == CheckState.Working })
        assertFalse(events.any { it.state == CheckState.Retrying })
        slow.complete(null)
        advanceUntilIdle()
        assertTrue(job.await().all { it.state == CheckState.Working })
    }

    @Test fun allFirstAttemptsPrecedeEveryConfirmation() = runBlocking {
        val calls = mutableListOf<Pair<String, Int>>()
        ConnectionChecks.run((0..11).map { "fixture-$it" }, { tag, attempt ->
            calls.add(tag to attempt)
            if (attempt == 1) null else 100
        }, {}, retryPauseMs = 0)
        assertEquals(12, calls.indexOfFirst { it.second == 2 })
        assertEquals(12, calls.take(12).map { it.first }.distinct().size)
    }

    @Test fun stalledPrefixDoesNotReceiveRetriesBeforeUntouchedTail() = runTest {
        val events = mutableListOf<CheckResult>()
        ConnectionChecks.run((0..11).map { "fixture-$it" }, { tag, attempt ->
            if (attempt == 1 && tag.removePrefix("fixture-").toInt() < 8) { delay(20); null } else 180
        }, events::add, retryPauseMs = 0, attemptDeadlineMs = 100)
        assertEquals(8, events.count { it.state == CheckState.Retrying })
        assertEquals(4, events.takeWhile { it.state != CheckState.Retrying }.count { it.state == CheckState.Working })
        assertEquals(12, events.count { it.finished })
    }

    @Test fun firstAttemptUsesOneAddressAndReservesTimeInsideFiveSeconds() = runBlocking {
        val urls = Collections.synchronizedList(mutableListOf<String>())
        val timeouts = Collections.synchronizedList(mutableListOf<Int>())
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply {
                urls.add(url); timeouts.add(timeoutMs); return ProbeReply(0, true)
            }
            override fun close() {}
        } })
        assertNull(transport.measure("fictional-target", 1))
        assertEquals(1, urls.size)
        assertEquals(listOf(4500), timeouts)
    }

    @Test fun confirmationStartsWithIndependentAddress() = runBlocking {
        val urls = Collections.synchronizedList(mutableListOf<String>())
        val transport = ProbeTransport(factory = { object : OwnedProbeClient {
            override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply {
                urls.add(url); return ProbeReply(0, true)
            }
            override fun close() {}
        } })
        assertNull(transport.measure("fictional-target", 2))
        assertEquals(ConnectionChecks.urls.reversed(), urls)
    }
}
