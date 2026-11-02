package io.nekohasekai.sfa.vendor

import io.nekohasekai.libbox.HTTPResponseWriteToProgressHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.nekohasekai.sfa.update.CancellableDownload
import java.io.Closeable
import java.io.File

class ApkDownloader : Closeable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private val started = java.util.concurrent.atomic.AtomicBoolean()
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean()
    private val client = Libbox.newHTTPClient().apply {
        modernTLS()
        keepAlive()
    }

    suspend fun download(url: String): File = withContext(Dispatchers.IO) {
        val cacheDir = File(Application.application.cacheDir, "updates")
        cacheDir.mkdirs()
        val apkFile = File.createTempFile("update-", ".apk", cacheDir)

        if (apkFile.exists()) apkFile.delete()

        val request = client.newRequest()
        request.setUserAgent(HTTPClient.userAgent)
        request.setURL(url)

        try {
            CancellableDownload.run(close = { closeClient() }) {
                started.set(true)
                try {
                    val response = request.execute()
                    response.writeToWithProgress(
                        apkFile.absolutePath,
                        object : HTTPResponseWriteToProgressHandler {
                            override fun update(progress: Long, total: Long) {
                                if (!cancelled.get()) UpdateState.downloadProgress.value =
                                    if (total > 0) progress.toFloat() / total.toFloat() else null
                            }
                        },
                    )
                } finally {
                    if (cancelled.get()) apkFile.delete()
                }
            }
            currentCoroutineContext().ensureActive()
            if (!apkFile.exists() || apkFile.length() == 0L) {
                throw Exception("Download failed: empty file")
            }
            UpdateState.saveApkPath(apkFile)
            apkFile
        } catch (e: Exception) {
            cancelled.set(true)
            apkFile.delete()
            throw e
        }
    }

    private fun closeClient() {
        if (closed.compareAndSet(false, true)) client.close()
    }

    override fun close() {
        if (!started.get()) closeClient()
    }
}
