package io.nekohasekai.sfa.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Внешний адрес, каким его видят сайты, и страна выхода. */
data class ExitIp(val ip: String, val country: String)

/**
 * Определяет адрес, под которым человек виден сайтам.
 *
 * Зачем в приложении: без этого результат своих действий не виден, и человек идёт
 * проверять на сторонние сайты — а там кэш страницы и посторонние расширения легко
 * вводят в заблуждение. В десктопной версии на этом уже теряли время: пользователь
 * менял сервер, а «адрес не менялся», хотя журнал ядра показывал разные узлы.
 *
 * ★ Ходим обычным java.net, а НЕ клиентом ядра (Libbox.newHTTPClient). Трафик самого
 * приложения идёт через tun наравне с чужим — приложение себя из туннеля не исключает,
 * оно исключает только то, что выбрал человек в маршрутизации по приложениям. Клиент
 * же ядра может защищать свои сокеты от туннеля, чтобы подписка обновлялась и при
 * сломанной связи, — и тогда мы показали бы НАСТОЯЩИЙ адрес вместо выходного, то есть
 * соврали бы ровно в том, ради чего всё и затевалось. Тем же обычным java.net меряет
 * связь и сторож (ConnectivityWatchdog) — по той же причине.
 *
 * Источника два: первый отдаёт ещё и страну, второй — только адрес, но почти никогда
 * не отказывает. Показать хоть что-то важнее, чем «не удалось определить».
 */
object ExitIpLookup {
    private const val PRIMARY_URL = "https://ipwho.is/?fields=ip,country,success"
    private const val FALLBACK_URL = "https://api.ipify.org"
    private const val TIMEOUT_MS = 10_000

    suspend fun lookup(): ExitIp? = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(get(PRIMARY_URL))
            if (json.optBoolean("success", true)) {
                val ip = json.optString("ip")
                if (ip.isNotBlank()) return@withContext ExitIp(ip, json.optString("country"))
            }
        }
        runCatching {
            val ip = get(FALLBACK_URL).trim()
            if (ip.isNotBlank()) return@withContext ExitIp(ip, "")
        }
        null
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
