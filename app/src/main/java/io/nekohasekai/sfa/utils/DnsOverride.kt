package io.nekohasekai.sfa.utils

import org.json.JSONObject

/**
 * Выбор DNS-сервера пользователем.
 *
 * Наш конфиг приходит с сервера уже с рабочим DNS (Cloudflare 1.1.1.1 через туннель). Эта
 * настройка позволяет заменить его — например, на AdGuard с блокировкой рекламы или на свой.
 * Подмешивается в конфиг НА ЛЕТУ при старте сервиса (как split tunnel), поэтому переживает
 * обновление подписки и работает с любым профилем.
 *
 * Приватность сохраняется: адрес меняется, но `detour` остаётся прежним — DNS по-прежнему идёт
 * ЧЕРЕЗ туннель, а не мимо. Провайдер не видит, какие имена резолвятся.
 *
 * ЛЮБОЙ сбой разбора или неверный адрес → возвращаем исходный конфиг. Сломанный DNS = «интернет
 * не работает», а это хуже, чем проигнорированная настройка.
 */
object DnsOverride {

    // Режимы (совпадают со значениями, которые пишет экран настроек).
    const val MODE_AUTO = "auto"          // как настроил сервис — не вмешиваемся (дефолт)
    const val MODE_CLOUDFLARE = "cloudflare"
    const val MODE_GOOGLE = "google"
    const val MODE_ADGUARD = "adguard"    // блокировка рекламы и трекеров на уровне DNS
    const val MODE_CUSTOM = "custom"

    /** Адрес пресета. Для custom адрес берётся из пользовательского поля. */
    fun presetServer(mode: String): String? = when (mode) {
        MODE_CLOUDFLARE -> "1.1.1.1"
        MODE_GOOGLE -> "8.8.8.8"
        MODE_ADGUARD -> "94.140.14.14"
        else -> null
    }

    /**
     * @param content исходный sing-box JSON
     * @param mode выбранный режим
     * @param customServer адрес для режима custom (IP-литерал)
     * @return конфиг с заменённым DNS-сервером, либо исходный
     */
    fun apply(content: String, mode: String, customServer: String): String {
        // auto — ничего не трогаем: оставляем DNS, который прислал сервис. Это дефолт и
        // самый безопасный путь: существующие клиенты работают ровно как раньше.
        if (mode == MODE_AUTO || mode.isBlank()) return content

        val server = if (mode == MODE_CUSTOM) customServer.trim() else presetServer(mode)
        if (server.isNullOrBlank() || !isIpLiteral(server)) return content

        return try {
            val root = JSONObject(content)
            val dns = root.optJSONObject("dns") ?: return content
            val servers = dns.optJSONArray("servers") ?: return content
            var replaced = false
            for (i in 0 until servers.length()) {
                val entry = servers.optJSONObject(i) ?: continue
                // Меняем только удалённый резолвер (тот, что идёт через туннель). dns-local
                // трогать нельзя — он для обхода по доменам.
                if (entry.optString("tag") == "dns-remote") {
                    entry.put("server", server)
                    replaced = true
                }
            }
            if (replaced) root.toString() else content
        } catch (e: Exception) {
            content
        }
    }

    /**
     * DNS-сервер задаётся IP-литералом, а не именем: резолвить имя внутри туннеля нечем (это же
     * и есть резолвер). Пускаем IPv4 и IPv6.
     */
    fun isIpLiteral(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        // IPv6 — грубо: есть двоеточие и только hex-символы.
        if (v.contains(':')) {
            return v.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
        }
        // IPv4 — четыре октета 0..255.
        val parts = v.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.isNotEmpty() && p.all(Char::isDigit) && p.toIntOrNull()?.let { it in 0..255 } == true }
    }
}
