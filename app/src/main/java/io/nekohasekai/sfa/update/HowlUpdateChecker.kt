package io.nekohasekai.sfa.update

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.ktx.unwrap
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable

/**
 * Проверка обновлений Howl на нашем сайте.
 *
 * Раньше приложение спрашивало обновления у репозитория sing-box — то есть у ЧУЖОГО проекта:
 * наших сборок оно там не нашло бы никогда, а нашло бы чужое приложение, которое поверх Howl
 * даже не установится (другой идентификатор и подпись). Теперь источник свой.
 *
 * Формат `latest.json` на сайте:
 * {"versionCode":712,"versionName":"1.0.0","url":"https://.../Howl.apk","size":30068838,"notes":"…"}
 *
 * Сравниваем по versionCode: он монотонно растёт с каждой сборкой, тогда как versionName
 * человеческий и может повторяться.
 */
class HowlUpdateChecker : Closeable {

    companion object {
        private const val MANIFEST_URL = "https://gethowl.app/downloads/howl/latest.json"
        const val RELEASE_PAGE = "https://gethowl.app/downloads/howl/"
    }

    private val client = Libbox.newHTTPClient().apply {
        modernTLS()
        keepAlive()
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun checkUpdate(): UpdateInfo? {
        val manifest = runCatching { fetchManifest() }.getOrNull() ?: return null
        if (manifest.versionCode <= BuildConfig.VERSION_CODE) return null
        if (manifest.url.isBlank()) return null
        return UpdateInfo(
            versionCode = manifest.versionCode,
            versionName = manifest.versionName,
            downloadUrl = manifest.url,
            releaseUrl = RELEASE_PAGE,
            releaseNotes = manifest.notes,
            isPrerelease = false,
            fileSize = manifest.size,
        )
    }

    private fun fetchManifest(): Manifest {
        val request = client.newRequest()
        request.setURL(MANIFEST_URL)
        request.setUserAgent(HTTPClient.userAgent)
        val response = request.execute()
        return json.decodeFromString(response.content.unwrap)
    }

    @Serializable
    private data class Manifest(
        @SerialName("versionCode") val versionCode: Int,
        @SerialName("versionName") val versionName: String = "",
        @SerialName("url") val url: String = "",
        @SerialName("size") val size: Long = 0,
        @SerialName("notes") val notes: String? = null,
    )

    override fun close() {
        runCatching { client.close() }
    }
}
