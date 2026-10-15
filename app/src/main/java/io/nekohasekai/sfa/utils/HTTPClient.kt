package io.nekohasekai.sfa.utils

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import java.io.Closeable
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import io.nekohasekai.sfa.profile.ProfileDocument

class HTTPClient : Closeable {
    companion object {
        val userAgent by lazy {
            var userAgent = "SFA/"
            userAgent += BuildConfig.VERSION_NAME
            userAgent += " ("
            userAgent += BuildConfig.VERSION_CODE
            userAgent += "; sing-box "
            userAgent += Libbox.version()
            userAgent += "; language "
            userAgent += Locale.getDefault().toLanguageTag().replace("-", "_")
            userAgent += ")"
            userAgent
        }
    }

    fun getString(url: String): String {
        // ★ 15.10.2026: ограничиваем ответ до выделения памяти; ошибки не содержат URL/токен.
        val deadline = System.nanoTime() + 30_000_000_000L
        var target = URL(url)
        repeat(6) {
            require(target.protocol in setOf("http", "https"))
            val connection = target.openConnection() as HttpURLConnection
            try {
                val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                require(remaining > 0)
                connection.connectTimeout = minOf(10_000, remaining)
                connection.readTimeout = minOf(10_000, remaining)
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", userAgent)
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val next = URL(target, connection.getHeaderField("Location") ?: error("redirect"))
                    require(target.protocol != "https" || next.protocol == "https")
                    target = next
                } else {
                    require(status in 200..299)
                    require((connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L) <= ProfileDocument.MAX_BYTES)
                    return connection.inputStream.use { stream ->
                        io.nekohasekai.sfa.profile.ProfilePayload.read(stream) {
                            require(System.nanoTime() < deadline && !Thread.currentThread().isInterrupted)
                            connection.readTimeout = minOf(10_000, ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1).toInt())
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        error("redirect limit")
    }

    override fun close() {
        // Connections are scoped to getString().
    }
}
