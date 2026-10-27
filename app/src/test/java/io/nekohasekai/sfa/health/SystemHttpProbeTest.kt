package io.nekohasekai.sfa.health

import io.nekohasekai.sfa.bg.WatchdogProbeResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

class SystemHttpProbeTest {
    private class Reply(private val code: Int = 204, private val error: Exception? = null) : HttpURLConnection(URL("https://control.invalid/generate_204")) {
        val closed = CountDownLatch(1)
        var redirectsAtRequest = true
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { closed.countDown() }
        override fun getResponseCode(): Int { redirectsAtRequest = instanceFollowRedirects; error?.let { throw it }; return code }
    }
    @Test fun onlyActualResolverFailureIsRecordedAsSystemDnsFailure() = runBlocking {
        var opened = false
        val probe = SystemHttpProbe(resolve = { throw UnknownHostException("fixture-token-and-host-must-not-leak") }, open = { opened = true; Reply() })
        val result = probe.run()
        assertEquals(WatchdogProbeResult.NoResponse, result.outcome)
        assertEquals(DnsFinding.Failed, result.dns)
        assertEquals(HttpFinding.Unknown, result.http)
        assertFalse(opened); assertFalse(result.toString().contains("fixture-token"))
    }
    @Test fun redirectIsNotAWorkingGenerate204Check() = runBlocking {
        val reply = Reply(302)
        val result = SystemHttpProbe(resolve = {}, open = { reply }).run()
        assertEquals(WatchdogProbeResult.NoResponse, result.outcome)
        assertEquals(DnsFinding.Resolved, result.dns)
        assertEquals(HttpFinding.UnexpectedStatus, result.http)
        assertFalse(reply.redirectsAtRequest)
        assertTrue(reply.closed.await(1, TimeUnit.SECONDS))
    }
    @Test fun ordinaryHtml200IsNotTheExpected204() = runBlocking {
        val result = SystemHttpProbe(resolve = {}, open = { Reply(200) }).run()
        assertEquals(WatchdogProbeResult.NoResponse, result.outcome)
        assertEquals(HttpFinding.UnexpectedStatus, result.http)
    }
    @Test fun completed204IsPositiveAndOwnConnectionIsClosed() = runBlocking {
        val reply = Reply()
        val result = SystemHttpProbe(resolve = {}, open = { reply }).run()
        assertEquals(WatchdogProbeResult.Reachable, result.outcome)
        assertEquals(DnsFinding.Resolved, result.dns)
        assertEquals(HttpFinding.Expected, result.http)
        assertTrue(reply.closed.await(1, TimeUnit.SECONDS))
    }
    @Test fun handshakeAndTransportFailuresDoNotBecomeDnsFailures() = runBlocking {
        val handshake = SystemHttpProbe(resolve = {}, open = { Reply(error = SSLHandshakeException("fixture secret")) }).run()
        assertEquals(HttpFinding.HandshakeFailure, handshake.http)
        assertEquals(DnsFinding.Resolved, handshake.dns)
        val transport = SystemHttpProbe(resolve = {}, open = { Reply(error = IOException("fixture secret")) }).run()
        assertEquals(HttpFinding.TransportFailure, transport.http)
        assertEquals(DnsFinding.Resolved, transport.dns)
        assertFalse(handshake.toString().contains("secret")); assertFalse(transport.toString().contains("secret"))
    }
    @Test fun anUnavailableCheckDoesNotInventAResolverFailure() = runBlocking {
        val result = SystemHttpProbe(resolve = { throw SecurityException("fixture secret") }, open = { Reply() }).run()
        assertEquals(WatchdogProbeResult.Unavailable, result.outcome)
        assertEquals(DnsFinding.Unknown, result.dns)
        assertEquals(HttpFinding.Unknown, result.http)
    }
    @Test fun controlTransportMustRemainHttps() {
        assertThrows(IllegalArgumentException::class.java) { SystemHttpProbe(url = URL("http://control.invalid/generate_204")) }
    }
}
