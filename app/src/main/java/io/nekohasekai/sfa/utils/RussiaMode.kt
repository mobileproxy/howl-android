package io.nekohasekai.sfa.utils

import android.content.pm.PackageInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Режим Россия» — российские сайты, банки и госсервисы идут НАПРЯМУЮ, мимо VPN.
 *
 * Зачем. Клиенту из РФ обычно нужно ровно две вещи одновременно: зарубежные ресурсы через VPN и
 * при этом рабочие банк/госуслуги/маркетплейсы. Многие российские сервисы блокируют или
 * ограничивают доступ с иностранных адресов (а некоторые госсервисы просто не открываются),
 * поэтому без обхода человек вынужден выключать VPN руками ради каждого перевода. Раньше для
 * этого нужно было вписывать домены в «Маршрутизацию» вручную и вручную же отмечать приложения —
 * ровно та ручная настройка, которой быть не должно.
 *
 * Что делает — два независимых слоя:
 *   • ДОМЕНЫ: правило «эти домены → direct» в конфиг ядра (плюс локальный DNS для них, иначе
 *     российский сайт вернёт адрес, привязанный к стране выхода VPN);
 *   • ПРИЛОЖЕНИЯ: список пакетов для per-app обхода — банковские приложения часто ходят не по
 *     доменам, а по своим адресам, и доменные правила их не ловят.
 *
 * Почему список зашит, а не тянется из подписки. Удалённый `rule_set` в конфиге — единая точка
 * отказа: не скачался (а в РФ это обычное дело) — ядро может не подняться вовсе. Встроенный
 * список работает всегда и офлайн. Обновляемость решается отдельным слоем поверх (кэш с сервера),
 * не ломая базовый сценарий.
 *
 * ★ Важно про ожидания пользователя: обход означает, что эти сайты видят НАСТОЯЩИЙ адрес. Для
 * банка это и нужно, но человеку это должно быть сказано явно — иначе повторится история с
 * Hiddify, где включённый по умолчанию обход региона выглядел как «VPN не работает, IP не
 * меняется». Поэтому режим по умолчанию ВЫКЛЮЧЕН и предлагается явным вопросом при первом запуске.
 */
object RussiaMode {

    /**
     * Российские зоны целиком. С ведущей точкой: `domain_suffix` сравнивает окончание строки, и
     * суффикс без точки («ru») поймал бы заодно «peru» и подобное.
     */
    private val ZONE_SUFFIXES = listOf(
        ".ru",
        ".su",
        ".xn--p1ai", // .рф в punycode — именно так домен приходит из SNI
        ".moscow",
        ".tatar",
    )

    /**
     * Российские сервисы на НЕ российских зонах — их зонами не покрыть. Отдельно перечислены и
     * домены раздачи контента: без них у ВК и Одноклассников не грузятся фото и видео.
     */
    private val EXTRA_DOMAINS = listOf(
        "vk.com",
        "vk.me",
        "userapi.com",
        "vk-cdn.net",
        "vkuservideo.net",
        "mycdn.me",
        "yandex.net",
        "yandex.com",
        "yastatic.net",
        "sberbank.com",
        "sberdevices.ru",
        "gosuslugi.ru",
        "tbank.ru",
        "t-bank.ru",
    )

    /**
     * Приложения. Подавляющее большинство российских приложений живёт на префиксе `ru.` —
     * Госуслуги (ru.rostel), Госключ (ru.gosuslugi.goskey), РЖД (ru.rzd.pass), Сбербанк
     * (ru.sberbankmobile), Альфа (ru.alfabank.*), ВТБ (ru.vtb24.*), СБПэй и Mir Pay (ru.nspk.*),
     * Ozon (ru.ozon.*), Мос.ру (ru.mos.*). Префикс покрывает и те приложения, которые человек
     * поставит в будущем, — список не придётся догонять.
     */
    private val APP_PREFIXES = listOf(
        "ru.",
        "com.yandex.",
    )

    /** Те, кто не на `ru.`: перечисляем поимённо. */
    private val APP_PACKAGES = setOf(
        "com.octopod.russianpost.client.android", // Почта России
        "com.idamob.tinkoff.android", // Т-Банк (Тинькофф)
        "com.gnivts.selfemployed", // Мой налог (ФНС)
        "com.vkontakte.android", // ВКонтакте
        "com.avito.android", // Авито
        "com.wildberries.ru", // Wildberries
        "com.dodopizza.app", // Додо Пицца
        "net.megafon.lk", // МегаФон
        "com.gazprombank.android.mobilebank.app", // Газпромбанк
        "com.sberbank.sbol", // Сбербанк (альтернативный пакет)
    )

    /** Наш собственный пакет и системные — в обход не отправляем. */
    private val SKIP_PREFIXES = listOf(
        "com.android.",
        "com.google.",
    )

    // ---------- Слой 1: маршрутизация доменов ----------

    /**
     * Дописывает в конфиг правило «российские домены → напрямую».
     * При выключенном режиме или неожиданной форме конфига возвращает вход как есть.
     */
    fun apply(content: String, enabled: Boolean): String {
        if (!enabled) return content
        return try {
            inject(content)
        } catch (e: Exception) {
            content
        }
    }

    private fun inject(content: String): String {
        val root = JSONObject(content)
        val route = root.optJSONObject("route") ?: return content

        val domains = EXTRA_DOMAINS
        val suffixes = ZONE_SUFFIXES + EXTRA_DOMAINS.map { ".$it" }

        // В КОНЕЦ массива: раньше нас должны отработать sniff (без него домен ещё не извлечён из
        // TLS SNI и совпадения не будет) и hijack-dns.
        val routeRules = route.optJSONArray("rules")
            ?: JSONArray().also { route.put("rules", it) }
        routeRules.put(
            JSONObject().apply {
                put("domain", jsonArrayOf(domains))
                put("domain_suffix", jsonArrayOf(suffixes))
                put("outbound", "direct")
            },
        )

        // Правило ссылается на выход «direct» — без него ядро не стартует.
        ensureDirectOutbound(root)

        // Резолвим эти домены локальным DNS: соединение и так идёт напрямую, зато сайт отдаёт
        // корректный местный адрес, а не геопривязку к стране выхода VPN.
        val dns = root.optJSONObject("dns")
        if (dns != null && hasDnsServer(dns, "dns-local")) {
            val dnsRules = dns.optJSONArray("rules")
                ?: JSONArray().also { dns.put("rules", it) }
            dnsRules.put(
                JSONObject().apply {
                    put("domain", jsonArrayOf(domains))
                    put("domain_suffix", jsonArrayOf(suffixes))
                    put("server", "dns-local")
                },
            )
        }
        return root.toString()
    }

    // ---------- Слой 2: приложения ----------

    /** Российское ли приложение — решаем по имени пакета. */
    fun isRussianApp(packageName: String, ownPackage: String): Boolean {
        if (packageName == ownPackage) return false
        if (SKIP_PREFIXES.any { packageName.startsWith(it) }) return false
        if (packageName in APP_PACKAGES) return true
        return APP_PREFIXES.any { packageName.startsWith(it) }
    }

    /** Отобрать российские приложения среди установленных. */
    fun scanInstalled(packages: List<PackageInfo>, ownPackage: String): Set<String> =
        packages.map { it.packageName }
            .filter { isRussianApp(it, ownPackage) }
            .toSet()

    // ---------- helpers ----------

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
