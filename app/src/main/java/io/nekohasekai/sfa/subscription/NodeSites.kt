package io.nekohasekai.sfa.subscription

import org.json.JSONObject

/**
 * Где физически стоит каждый выход профиля и каким протоколом он ходит — для карусели сторожа.
 *
 * ★ Зачем. Карусель предпочитала «другой протокол», не зная адресов, и потому охотно уводила
 * с VLESS Франкфурт на Shadowsocks Франкфурт — на ТОТ ЖЕ сервер. Режут у нас чаще по адресу
 * (Хельсинки 26.09 — точечно по IP), и соседний протокол на том же адресе страдает так же.
 * 04.10.2026 на Windows так и вышло: 14 минут рваной связи на соседях, пока сторож не вернул
 * исходный узел. Протокол же она определяла по первому символу значка, а 🛡 VLESS и 🐺
 * AmneziaWG начинаются с одной и той же половинки суррогатной пары — для неё это был один
 * протокол. Здесь и адрес, и протокол берутся из самого конфига.
 *
 * Сайт — адрес сервера. У ShadowTLS видимый выход Shadowsocks адреса не несёт, он у скрытого
 * набирателя (detour). У OpenConnect вместо адреса имя («7734.vpn.how:738») — такой выход
 * привязываем к адресу соседей по локации (та же подпись до « · »). Порт Windows-клиента,
 * src/Howl.Core/NodeSites.cs.
 */
object NodeSites {

    data class Site(val host: String, val proto: String)

    @Volatile
    var current: Map<String, Site> = emptyMap()
        private set

    fun scan(content: String) {
        current = runCatching { parse(JSONObject(content)) }.getOrDefault(emptyMap())
    }

    fun clear() {
        current = emptyMap()
    }

    fun parse(root: JSONObject): Map<String, Site> {
        val byTag = LinkedHashMap<String, JSONObject>()
        for (key in listOf("outbounds", "endpoints")) {
            val arr = root.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val tag = o.optString("tag").takeIf { it.isNotBlank() } ?: continue
                if (!byTag.containsKey(tag)) byTag[tag] = o
            }
        }

        data class Raw(val tag: String, val addr: String?, val proto: String, val label: String)

        val raw = mutableListOf<Raw>()
        for ((tag, o) in byTag) {
            val type = o.optString("type")
            if (type in SERVICE_TYPES) continue
            var addr = address(o)
            var proto = type
            val detour = o.optString("detour").takeIf { it.isNotBlank() }
            val dialer = detour?.let { byTag[it] }
            if (dialer != null) {
                addr = address(dialer) ?: addr
                proto = dialer.optString("type").ifBlank { proto }
            }
            raw.add(Raw(tag, addr, proto, label(tag)))
        }

        // Подпись локации → адрес, если хоть у одного выхода в ней адрес числовой.
        val labelIp = HashMap<String, String>()
        for (r in raw) {
            if (r.addr != null && isIpLiteral(r.addr) && r.label.isNotEmpty() && !labelIp.containsKey(r.label)) {
                labelIp[r.label] = r.addr
            }
        }

        val result = LinkedHashMap<String, Site>()
        for (r in raw) {
            val host = when {
                r.addr != null && isIpLiteral(r.addr) -> r.addr
                labelIp.containsKey(r.label) -> labelIp.getValue(r.label)
                else -> r.addr ?: r.label
            }
            result[r.tag] = Site(host, r.proto)
        }
        return result
    }

    /** «🛡 DE Франкфурт · VLESS» → «DE Франкфурт». Без « · » — пусто. */
    fun label(tag: String): String {
        val cut = tag.lastIndexOf(" · ")
        if (cut <= 0) return ""
        val head = tag.substring(0, cut)
        var start = 0
        while (start < head.length && !head[start].isLetterOrDigit()) start++
        return head.substring(start).trim()
    }

    /** Адрес без порта и схемы: у OpenConnect в «server» лежит «7734.vpn.how:738». */
    fun hostOnly(server: String): String {
        var s = server.trim()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        val slash = s.indexOf('/')
        if (slash >= 0) s = s.substring(0, slash)
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            return if (close > 0) s.substring(1, close) else s
        }
        // Ровно одно двоеточие — «хост:порт»; больше — голый IPv6.
        val colon = s.indexOf(':')
        return if (colon > 0 && colon == s.lastIndexOf(':')) s.substring(0, colon) else s
    }

    /**
     * Числовой ли адрес. Без InetAddress намеренно: для имени он полез бы в DNS, а здесь нужен
     * только разбор строки.
     */
    fun isIpLiteral(s: String): Boolean {
        if (s.contains(':')) return s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        val parts = s.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255 }
    }

    private fun address(o: JSONObject): String? {
        o.optString("server").takeIf { it.isNotBlank() }?.let { return hostOnly(it) }
        val peers = o.optJSONArray("peers")
        val first = peers?.optJSONObject(0)
        first?.optString("address")?.takeIf { it.isNotBlank() }?.let { return hostOnly(it) }
        return null
    }

    private val SERVICE_TYPES = setOf("selector", "urltest", "direct", "block", "dns")
}
