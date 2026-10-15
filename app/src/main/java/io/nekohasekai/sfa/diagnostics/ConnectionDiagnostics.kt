package io.nekohasekai.sfa.diagnostics

import android.os.Build
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.bg.ConnectivityWatchdog
import io.nekohasekai.sfa.utils.AppEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

object ConnectionDiagnostics {
    private val requests = Executors.newFixedThreadPool(2) { work ->
        Thread(work, "Howl diagnostic request").apply { isDaemon = true }
    }
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
                check(!Thread.currentThread().isInterrupted)
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
            val downloadStart = System.nanoTime()
            val downloaded = request("https://gethowl.app/downloads/howl/win/Howl-Setup.exe", 10_000, deadline) { connection ->
                connection.setRequestProperty("Range", "bytes=0-1048575")
                if (connection.responseCode !in setOf(200, 206)) return@request -1
                connection.inputStream.use { DiagnosticLimits.readBounded(it, ::checkDeadline) }
            }
            checkDeadline()
            ConnectionReport(
                BuildConfig.VERSION_NAME, Libbox.version(), Build.VERSION.SDK_INT,
                startedAt, System.currentTimeMillis(), before, snapshot(), delays, downloaded?.coerceAtLeast(0) ?: 0,
                if (downloaded != null && downloaded > 0) (System.nanoTime() - downloadStart) / 1_000_000 else null,
                EventSummary.from(AppEventLog.history(2000)),
            )
        } finally {
            running = false
        }
    }

    private suspend fun <T> request(url: String, timeout: Int, deadline: Long, block: (HttpURLConnection) -> T): T? {
        val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
        return withTimeoutOrNull(minOf(timeout.toLong(), remaining)) {
            suspendCancellableCoroutine { continuation ->
                val connection = URL(url).openConnection() as HttpURLConnection
                val task = requests.submit {
                    val value = try {
                        connection.connectTimeout = minOf(timeout.toLong(), remaining).toInt()
                        connection.readTimeout = minOf(timeout.toLong(), remaining).toInt()
                        connection.instanceFollowRedirects = false
                        connection.useCaches = false
                        block(connection)
                    } catch (_: Exception) {
                        null
                    } finally {
                        connection.disconnect()
                    }
                    if (continuation.isActive) continuation.resume(value)
                }
                continuation.invokeOnCancellation {
                    connection.disconnect()
                    task.cancel(true)
                }
            }
        }
    }
}
