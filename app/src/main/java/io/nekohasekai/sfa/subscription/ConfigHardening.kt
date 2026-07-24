package io.nekohasekai.sfa.subscription

import org.json.JSONArray
import org.json.JSONObject

/**
 * Доводит ЛЮБОЙ профиль до наших требований по бесперебойности.
 *
 * Зачем. Всё, что мы вылечили, живёт в конфиге, который генерирует наш `sub.php`. Профиль,
 * пришедший от стороннего сервиса, этого не унаследует — и будет работать заметно хуже нашего,
 * а жалобы придут нам. Здесь чужой конфиг получает те же гарантии.
 *
 * Что делаем и почему именно это:
 *
 *  • **IPv6-адрес на tun.** Без него IPv6-трафик вообще не заходит в туннель и уходит напрямую,
 *    с настоящего адреса человека. Это утечка в основной функции продукта.
 *  • **Отбой IPv6 наружу** (`ip_version=6` → `reject`). Донести IPv6 до сайта мы всё равно не
 *    можем: у wireguard-эндпоинтов нет своего IPv6-адреса, да и не у каждого узла он есть.
 *    Без этого правила такие соединения не падают, а ВИСЯТ по 15–25 секунд — журнал 24.07
 *    показал ровно это на сети Telegram. `method=default` — это RST/ICMP unreachable, то есть
 *    приложение видит отказ мгновенно и берёт IPv4.
 *
 * Принцип — **только добавлять, ничего не переписывать.** Чужой конфиг мы не знаем: его DNS,
 * маршруты и outbound'ы трогать нельзя, иначе сломаем работающее. Если нужное уже есть —
 * оставляем как есть. Если конфиг не разобрался — возвращаем исходный текст без изменений:
 * пусть лучше профиль работает без закалки, чем не работает вовсе.
 */
object ConfigHardening {

    /** Тот же адрес, что генерирует `sub.php`, — документированный дефолт sing-box. */
    private const val TUN_IPV6 = "fdfe:dcba:9876::1/126"

    fun apply(content: String): String = runCatching {
        val root = JSONObject(content)
        var changed = false
        if (addTunIPv6(root)) changed = true
        if (addIPv6Reject(root)) changed = true
        if (changed) root.toString(2) else content
    }.getOrDefault(content)

    /** Адрес IPv6 на tun-инбаунде — чтобы IPv6 заходил В туннель, а не мимо него. */
    private fun addTunIPv6(root: JSONObject): Boolean {
        val inbounds = root.optJSONArray("inbounds") ?: return false
        var changed = false
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("type") != "tun") continue
            val address = inbound.optJSONArray("address") ?: JSONArray().also {
                inbound.put("address", it)
            }
            val hasIPv6 = (0 until address.length()).any { address.optString(it).contains(':') }
            if (!hasIPv6) {
                address.put(TUN_IPV6)
                changed = true
            }
        }
        return changed
    }

    /**
     * Правило отбоя дописываем В КОНЕЦ: всё, что автор конфига увёл раньше (обход по доменам,
     * свои маршруты), должно отработать первым — мы не вправе это перебивать.
     */
    private fun addIPv6Reject(root: JSONObject): Boolean {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            // Уже есть такое правило — второй раз не добавляем.
            if (rule.opt("ip_version")?.toString() == "6" && rule.optString("action") == "reject") {
                return false
            }
        }
        rules.put(
            JSONObject()
                .put("ip_version", 6)
                .put("action", "reject")
                .put("method", "default"),
        )
        return true
    }
}
