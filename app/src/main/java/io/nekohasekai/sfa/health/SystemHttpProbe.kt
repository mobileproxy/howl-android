package io.nekohasekai.sfa.health

import io.nekohasekai.sfa.bg.WatchdogProbeResult
import io.nekohasekai.sfa.bg.WatchdogProbeRunner
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException

data class SystemHttpFinding(val outcome: WatchdogProbeResult, val dns: DnsFinding, val http: HttpFinding)
class SystemHttpProbe(private val runner: WatchdogProbeRunner = WatchdogProbeRunner(),
    private val resolve: (String) -> Unit = { InetAddress.getByName(it); Unit },
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val url: URL = URL("https://www.gstatic.com/generate_204"),
    private val timeoutMs: Long = 8000) {
    init { require(url.protocol == "https" && timeoutMs in 1..Int.MAX_VALUE.toLong()) }
    suspend fun run(): SystemHttpFinding {
        val dns = AtomicReference(DnsFinding.Unknown)
        val http = AtomicReference(HttpFinding.Unknown)
        val outcome = runner.run(timeoutMs) { resources ->
            try { resolve(url.host); dns.set(DnsFinding.Resolved) }
            catch (_: UnknownHostException) { dns.set(DnsFinding.Failed); return@run false }
            try {
                val connection = open(url)
                resources.own(Closeable { connection.disconnect() })
                connection.connectTimeout = timeoutMs.toInt()
                connection.readTimeout = timeoutMs.toInt()
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                val expected = connection.responseCode == 204
                http.set(if (expected) HttpFinding.Expected else HttpFinding.UnexpectedStatus)
                expected
            } catch (_: SSLHandshakeException) {
                http.set(HttpFinding.HandshakeFailure); false
            } catch (_: UnknownHostException) {
                dns.set(DnsFinding.Failed); false
            } catch (_: IOException) {
                http.set(HttpFinding.TransportFailure); false
            }
        }
        // No observations from a late/stuck/rejected operation are exported as a diagnosis.
        return if (outcome == WatchdogProbeResult.Unavailable)
            SystemHttpFinding(outcome, DnsFinding.Unknown, HttpFinding.Unknown)
        else SystemHttpFinding(outcome, dns.get(), http.get())
    }
}
