package io.nekohasekai.sfa.diagnostics

import android.os.Build
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.bg.ConnectivityWatchdog
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.profile.ProfileSafety
import io.nekohasekai.sfa.utils.AppEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ConnectionDiagnostics {
    @Volatile var running = false
        private set

    private fun snapshot(): NodeSnapshot {
        val tag = ConnectivityWatchdog.diagnosticNode()
        return NodeSnapshot.of(tag, BoxService.diagnosticProtocol(tag))
    }

    /** ★ 15.10.2026: только чтение и пробы; нет select/reload и автоматической отправки. */
    suspend fun run(): ConnectionReport = withContext(Dispatchers.IO) {
        synchronized(this@ConnectionDiagnostics) {
            check(!running && BoxService.running)
            running = true
        }
        try {
            val context = currentCoroutineContext()
            val startedAt = System.currentTimeMillis()
            val deadline = System.nanoTime() + 40_000_000_000L
            fun checkDeadline() {
                context.ensureActive()
                check(System.nanoTime() < deadline)
            }
            val before = snapshot()
            val delays = mutableListOf<Long?>()
            repeat(3) {
                checkDeadline()
                val start = System.nanoTime()
                val success = request("https://www.gstatic.com/generate_204", 6000, deadline) { connection ->
                    connection.responseCode in 200..299
                }
                delays.add(if (success == true) (System.nanoTime() - start) / 1_000_000 else null)
            }
            var bytes = 0
            val downloadStart = System.nanoTime()
            val downloaded = request("https://gethowl.app/downloads/howl/win/Howl-Setup.exe", 10_000, deadline) { connection ->
                connection.setRequestProperty("Range", "bytes=0-1048575")
                if (connection.responseCode !in setOf(200, 206)) return@request false
                connection.inputStream.use { bytes = DiagnosticLimits.readBounded(it, ::checkDeadline) }
                true
            }
            checkDeadline()
            ConnectionReport(
                BuildConfig.VERSION_NAME, Libbox.version(), Build.VERSION.SDK_INT,
                startedAt, System.currentTimeMillis(), before, snapshot(), delays, bytes,
                if (downloaded == true) (System.nanoTime() - downloadStart) / 1_000_000 else null,
                EventSummary.from(AppEventLog.history(2000)),
            )
        } finally {
            running = false
        }
    }

    private inline fun <T> request(url: String, timeout: Int, deadline: Long, block: (HttpURLConnection) -> T): T? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1).toInt()
            connection.connectTimeout = minOf(timeout, remaining)
            connection.readTimeout = minOf(timeout, remaining)
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            block(connection)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
