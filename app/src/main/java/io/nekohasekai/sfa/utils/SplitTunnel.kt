package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN

/**
 * Раздельное туннелирование: домены, которые идут МИМО VPN, напрямую.
 *
 * Правила подмешиваются в конфиг НА ЛЕТУ при старте сервиса, а не сохраняются в файл профиля.
 * Профиль у нас удалённый (подписка) и перезаписывается при каждом обновлении — локальная
 * правка в нём просто исчезла бы. Так список переживает обновления и работает с любым профилем.
 *
 * Это дополнение к списку из личного кабинета, а не замена: серверные правила уже лежат
 * в скачанном конфиге, наше правило дописывается следом. Пересечение безвредно.
 *
 * ЛЮБОЙ сбой разбора → возвращаем исходный конфиг. Неоткрывшийся сайт-исключение это
 * неудобство, а упавшее подключение — сломанный продукт.
 */
object SplitTunnel {

    private const val MAX_DOMAINS = 50

    private val DOMAIN_REGEX =
        Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    /**
     * Разбирает пользовательский ввод (домен на строку) в чистый список.
     * Терпим к тому, как люди реально пишут: со схемой, с путём, с «*.», с портом, кириллицей.
     * Всё непохожее на домен молча отбрасываем.
     */
    fun parseDomains(raw: String): List<String> {
        val out = LinkedHashSet<String>()
        for (part in raw.split('\n', '\r', ',', ';', ' ', '\t')) {
            var d = part.trim().lowercase()
            if (d.isEmpty()) continue
            d = d.replace(Regex("^[a-z0-9+.\\-]+://"), "")   // схема
            d = d.replace(Regex("[/?#].*$"), "")             // путь и параметры
            d = d.replace(Regex("^\\*\\."), "")              // *.foo.ru → foo.ru
            d = d.replace(Regex(":\\d+$"), "")               // порт
            d = d.trim('.')
            if (d.isEmpty()) continue
            if (d.any { it.code > 127 }) {
                // кириллические домены (госуслуги.рф) → punycode, иначе ядро их не сматчит
                val ascii = try {
                    IDN.toASCII(d)
                } catch (e: Exception) {
                    null
                }
                if (ascii == null) continue
                d = ascii
            }
            if (d.length > 253) continue
            if (!DOMAIN_REGEX.matches(d)) continue
            out.add(d)
            if (out.size >= MAX_DOMAINS) break
        }
        return out.toList()
    }

    /**
     * Дописывает в конфиг правило «эти домены → напрямую».
     * @param content исходный sing-box JSON
     * @param raw сырой пользовательский ввод из настроек
     * @return конфиг с правилом, либо исходный — если список пуст или конфиг неожиданной формы
     */
    fun apply(content: String, raw: String): String {
        val domains = parseDomains(raw)
        if (domains.isEmpty()) return content
        return try {
            val root = JSONObject(content)
            val route = root.optJSONObject("route") ?: return content

            // Правило дописываем В КОНЕЦ массива: до него должны отработать sniff (иначе домен
            // ещё не извлечён из TLS SNI и совпадения не будет) и hijack-dns.
            val routeRules = route.optJSONArray("rules")
                ?: JSONArray().also { route.put("rules", it) }
            routeRules.put(
                JSONObject().apply {
                    put("domain", jsonArrayOf(domains))
                    put("domain_suffix", jsonArrayOf(domains.map { ".$it" }))
                    put("outbound", "direct")
                },
            )

            // Правило ссылается на выход «direct» — если его в конфиге нет, ядро не стартует.
            ensureDirectOutbound(root)

            // Обходимые домены резолвим локально: соединение к ним и так идёт напрямую, зато
            // сайт отдаёт корректный местный адрес, а не геопривязку к стране выхода VPN.
            // Если в конфиге нет сервера dns-local — просто пропускаем, маршрут всё равно работает.
            val dns = root.optJSONObject("dns")
            if (dns != null && hasDnsServer(dns, "dns-local")) {
                val dnsRules = dns.optJSONArray("rules")
                    ?: JSONArray().also { dns.put("rules", it) }
                dnsRules.put(
                    JSONObject().apply {
                        put("domain", jsonArrayOf(domains))
                        put("domain_suffix", jsonArrayOf(domains.map { ".$it" }))
                        put("server", "dns-local")
                    },
                )
            }
            root.toString()
        } catch (e: Exception) {
            content
        }
    }

    private fun jsonArrayOf(values: List<String>): JSONArray {
        val array = JSONArray()
        values.forEach { array.put(it) }
        return array
    }

    private fun hasDnsServer(dns: JSONObject, tag: String): Boolean {
        val servers = dns.optJSONArray("servers") ?: return false
        for (i in 0 until servers.length()) {
            if (servers.optJSONObject(i)?.optString("tag") == tag) return true
        }
        return false
    }

    private fun ensureDirectOutbound(root: JSONObject) {
        val outbounds = root.optJSONArray("outbounds")
            ?: JSONArray().also { root.put("outbounds", it) }
        for (i in 0 until outbounds.length()) {
            if (outbounds.optJSONObject(i)?.optString("tag") == "direct") return
        }
        outbounds.put(
            JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            },
        )
    }
}
