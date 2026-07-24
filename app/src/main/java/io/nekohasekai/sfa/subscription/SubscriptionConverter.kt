package io.nekohasekai.sfa.subscription

import android.util.Base64
import io.nekohasekai.libbox.Libbox
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Приводит содержимое подписки к sing-box JSON.
 *
 * Зачем. Приложение умеет только sing-box JSON — импорт валидируется через `Libbox.checkConfig`.
 * Но большинство сторонних сервисов отдаёт подписку СПИСКОМ ССЫЛОК (`vless://`, `vmess://`,
 * `ss://`, `trojan://`, `hysteria2://`, `tuic://`), обычно ещё и упакованным в base64. Без
 * разбора такая подписка просто не импортируется.
 *
 * Где это делается. **Только на устройстве.** Серверный конвертер означал бы, что ключи от
 * чужих сервисов идут через наш сервер — это и приватность пользователя, и ответственность,
 * которая нам не нужна.
 *
 * Главный принцип: **если контент уже валидный sing-box JSON, он возвращается БЕЗ ИЗМЕНЕНИЙ.**
 * Наша собственная подписка и чужие sing-box-подписки идут прежним путём, конвертер для них
 * не существует. Разбор включается только тогда, когда ядро контент не приняло.
 *
 * Форма собираемого конфига зафиксирована в `config-test/howl-thirdparty-sample.json` и
 * проверяется настоящим ядром в CI (workflow `validate-config.yml`) — Kotlin здесь обязан
 * собирать ровно такие outbound'ы.
 */
object SubscriptionConverter {

    /** Теги должны совпадать с тем, что генерирует наш `sub.php`: на них смотрит интерфейс. */
    private const val SELECTOR_TAG = "Howl"
    private const val AUTO_TAG = "auto"
    private const val PROBE_URL = "https://www.gstatic.com/generate_204"

    private val SCHEMES = listOf(
        "vless://", "vmess://", "ss://", "trojan://", "hysteria2://", "hy2://", "tuic://",
    )

    /**
     * @param content сырое содержимое подписки (файл, тело HTTP-ответа или данные QR-кода)
     * @param fallbackName имя профиля — используется, если у ссылок нет своих названий
     * @return валидный sing-box JSON
     * @throws Exception если это ни sing-box JSON, ни распознаваемый список ссылок; текст
     * ошибки при этом — исходный от ядра, чтобы пользователь видел суть, а не «не смог разобрать»
     */
    fun normalize(content: String, fallbackName: String): String {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            Libbox.checkConfig(trimmed)
            return trimmed
        }

        // 1. Уже sing-box JSON? Тогда ничего не трогаем — это основной путь.
        runCatching { Libbox.checkConfig(trimmed) }.onSuccess { return trimmed }

        // 2. Похоже на список ссылок?
        val uris = extractUris(trimmed)
        if (uris.isEmpty()) {
            // Не список — пусть пользователь увидит настоящую ошибку разбора от ядра.
            Libbox.checkConfig(trimmed)
            return trimmed
        }

        val outbounds = mutableListOf<JSONObject>()
        val used = mutableMapOf<String, Int>()
        for (uri in uris) {
            // vmess стоит особняком: это не URI, а base64 от JSON в формате v2rayN.
            val outbound = if (uri.startsWith("vmess://", ignoreCase = true)) {
                vmessOutbound(uri)
            } else {
                runCatching { toOutbound(parse(uri)) }.getOrNull()
            } ?: continue
            // Повторяющиеся имена локаций — обычное дело у подписок. Дубли тегов ядро не
            // примет, поэтому нумеруем: «Париж», «Париж 2», «Париж 3».
            var tag = outbound.optString("tag").ifBlank { fallbackName }
            val seen = used[tag]
            if (seen != null) {
                used[tag] = seen + 1
                tag = "$tag ${seen + 1}"
            } else {
                used[tag] = 1
            }
            outbound.put("tag", tag)
            outbounds.add(outbound)
        }
        if (outbounds.isEmpty()) {
            error("Подписка распознана как список ссылок, но ни одна ссылка не поддерживается")
        }

        val converted = build(outbounds)
        Libbox.checkConfig(converted)
        return converted
    }

    // ─────────────────────────── разбор списка ───────────────────────────

    private fun extractUris(raw: String): List<String> {
        splitUris(raw).let { if (it.isNotEmpty()) return it }
        // Подписки почти всегда приходят в base64 одной длинной строкой.
        val decoded = decodeBase64(raw.filterNot { it == '\n' || it == '\r' || it == ' ' }) { it.contains("://") }
            ?: return emptyList()
        return splitUris(decoded)
    }

    private fun splitUris(text: String): List<String> = text
        .lineSequence()
        .map { it.trim() }
        .filter { line -> SCHEMES.any { line.startsWith(it, ignoreCase = true) } }
        .toList()

    /**
     * base64 в подписках встречается и обычный, и url-safe, и без выравнивания — перебираем.
     *
     * `isValid` нужен, потому что мусорный вариант декодирования тоже «успешен»: получаются
     * произвольные байты. Проверка содержимого — единственный надёжный признак, что вариант
     * угадан верно. Для списка ссылок это `://`, для vmess — фигурная скобка JSON.
     */
    private fun decodeBase64(text: String, isValid: (String) -> Boolean): String? {
        if (text.isEmpty()) return null
        for (flags in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE, Base64.DEFAULT or Base64.NO_PADDING)) {
            val bytes = runCatching { Base64.decode(text, flags or Base64.NO_WRAP) }.getOrNull() ?: continue
            val decoded = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: continue
            if (isValid(decoded)) return decoded
        }
        return null
    }

    // ─────────────────────────── разбор одной ссылки ───────────────────────────

    private class Link(
        val scheme: String,
        val userInfo: String,
        val host: String,
        val port: Int,
        val path: String,
        val query: Map<String, String>,
        val name: String,
    )

    /**
     * Свой разбор, а не `java.net.URI`: в подписках сплошь и рядом эмодзи и пробелы в `#имени`
     * и нестандартный userinfo — штатный парсер на таком падает.
     */
    private fun parse(uri: String): Link {
        val schemeEnd = uri.indexOf("://")
        val scheme = uri.substring(0, schemeEnd).lowercase()
        var rest = uri.substring(schemeEnd + 3)

        var name = ""
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            name = decodeComponent(rest.substring(hash + 1))
            rest = rest.substring(0, hash)
        }

        var query = emptyMap<String, String>()
        val qm = rest.indexOf('?')
        if (qm >= 0) {
            query = parseQuery(rest.substring(qm + 1))
            rest = rest.substring(0, qm)
        }

        var userInfo = ""
        val at = rest.lastIndexOf('@')
        if (at >= 0) {
            userInfo = rest.substring(0, at)
            rest = rest.substring(at + 1)
        }

        var path = ""
        // Косую ищем после закрывающей скобки IPv6, иначе разрежем адрес.
        val hostEnd = if (rest.startsWith("[")) rest.indexOf(']').coerceAtLeast(0) else 0
        val slash = rest.indexOf('/', hostEnd)
        if (slash >= 0) {
            path = rest.substring(slash)
            rest = rest.substring(0, slash)
        }

        val host: String
        val port: Int
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            host = rest.substring(1, close)
            port = rest.substring(close + 1).removePrefix(":").toIntOrNull() ?: 443
        } else {
            val colon = rest.lastIndexOf(':')
            if (colon >= 0) {
                host = rest.substring(0, colon)
                port = rest.substring(colon + 1).toIntOrNull() ?: 443
            } else {
                host = rest
                port = 443
            }
        }

        return Link(
            scheme = scheme,
            userInfo = userInfo,
            host = host,
            port = port,
            path = path,
            query = query,
            name = name.ifBlank { "$host:$port" },
        )
    }

    private fun parseQuery(raw: String): Map<String, String> = raw
        .split('&')
        .mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val eq = pair.indexOf('=')
            if (eq < 0) decodeComponent(pair) to "" else decodeComponent(pair.substring(0, eq)) to decodeComponent(pair.substring(eq + 1))
        }
        .toMap()

    private fun decodeComponent(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    // ─────────────────────────── ссылка → outbound ───────────────────────────

    private fun toOutbound(link: Link): JSONObject = when (link.scheme) {
        "vless" -> vless(link)
        "ss" -> shadowsocks(link)
        "trojan" -> trojan(link)
        "hysteria2", "hy2" -> hysteria2(link)
        "tuic" -> tuic(link)
        else -> error("неизвестная схема ${link.scheme}")
    }

    private fun vless(link: Link): JSONObject {
        val uuid = decodeComponent(link.userInfo)
        require(uuid.isNotBlank()) { "vless без uuid" }
        val o = JSONObject()
            .put("type", "vless")
            .put("tag", link.name)
            .put("server", link.host)
            .put("server_port", link.port)
            .put("uuid", uuid)
        val security = link.query["security"]?.lowercase().orEmpty()
        // flow имеет смысл только поверх TLS/Reality — без них ядро соединение не поднимет.
        link.query["flow"]?.takeIf { it.isNotBlank() && security != "none" && security.isNotBlank() }
            ?.let { o.put("flow", it) }
        tlsFor(link, security)?.let { o.put("tls", it) }
        transportFor(link)?.let { o.put("transport", it) }
        return o
    }

    /**
     * vmess почти всегда приходит в формате v2rayN: base64 от JSON, а не обычная ссылка.
     * Поля там свои (`add`/`id`/`aid`/`scy`/`net`), поэтому разбор отдельный.
     */
    private fun vmessFromJson(raw: String): JSONObject {
        val j = JSONObject(raw)
        val host = j.optString("add")
        val port = j.optString("port").toIntOrNull() ?: j.optInt("port", 443)
        val name = j.optString("ps").ifBlank { "$host:$port" }
        val o = JSONObject()
            .put("type", "vmess")
            .put("tag", name)
            .put("server", host)
            .put("server_port", port)
            .put("uuid", j.optString("id"))
            .put("alter_id", j.optString("aid").toIntOrNull() ?: 0)
            .put("security", j.optString("scy").ifBlank { "auto" })

        val tlsMode = j.optString("tls").lowercase()
        if (tlsMode == "tls" || tlsMode == "reality") {
            val sni = j.optString("sni").ifBlank { j.optString("host") }.ifBlank { host }
            val tls = JSONObject().put("enabled", true).put("server_name", sni)
            j.optString("alpn").takeIf { it.isNotBlank() }?.let { tls.put("alpn", JSONArray(it.split(","))) }
            j.optString("fp").takeIf { it.isNotBlank() }
                ?.let { tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
            o.put("tls", tls)
        }

        when (j.optString("net").lowercase()) {
            "ws" -> {
                val t = JSONObject().put("type", "ws")
                j.optString("path").takeIf { it.isNotBlank() }?.let { t.put("path", it) }
                j.optString("host").takeIf { it.isNotBlank() }
                    ?.let { t.put("headers", JSONObject().put("Host", it)) }
                o.put("transport", t)
            }
            "grpc" -> o.put(
                "transport",
                JSONObject().put("type", "grpc").put("service_name", j.optString("path")),
            )
            "h2", "http" -> {
                val t = JSONObject().put("type", "http")
                j.optString("path").takeIf { it.isNotBlank() }?.let { t.put("path", it) }
                j.optString("host").takeIf { it.isNotBlank() }
                    ?.let { t.put("host", JSONArray(it.split(","))) }
                o.put("transport", t)
            }
        }
        return o
    }

    private fun shadowsocks(link: Link): JSONObject {
        // Две формы: SIP002 (base64(method:password)@host:port) и старая, где в base64
        // упакована вся строка целиком.
        val info = decodeBase64Plain(link.userInfo) ?: decodeComponent(link.userInfo)
        val colon = info.indexOf(':')
        require(colon > 0) { "ss без метода/пароля" }
        return JSONObject()
            .put("type", "shadowsocks")
            .put("tag", link.name)
            .put("server", link.host)
            .put("server_port", link.port)
            .put("method", info.substring(0, colon))
            .put("password", info.substring(colon + 1))
    }

    private fun trojan(link: Link): JSONObject {
        val password = decodeComponent(link.userInfo)
        require(password.isNotBlank()) { "trojan без пароля" }
        val o = JSONObject()
            .put("type", "trojan")
            .put("tag", link.name)
            .put("server", link.host)
            .put("server_port", link.port)
            .put("password", password)
        // У trojan TLS подразумевается, даже когда security в ссылке не указан.
        o.put("tls", tlsFor(link, link.query["security"]?.lowercase() ?: "tls") ?: defaultTls(link))
        transportFor(link)?.let { o.put("transport", it) }
        return o
    }

    private fun hysteria2(link: Link): JSONObject {
        val password = decodeComponent(link.userInfo)
        require(password.isNotBlank()) { "hysteria2 без пароля" }
        val o = JSONObject()
            .put("type", "hysteria2")
            .put("tag", link.name)
            .put("server", link.host)
            .put("server_port", link.port)
            .put("password", password)
        link.query["obfs"]?.takeIf { it.isNotBlank() }?.let { obfs ->
            o.put(
                "obfs",
                JSONObject()
                    .put("type", obfs)
                    .put("password", link.query["obfs-password"].orEmpty()),
            )
        }
        o.put("tls", defaultTls(link))
        return o
    }

    private fun tuic(link: Link): JSONObject {
        // userinfo здесь — «uuid:пароль».
        val info = decodeComponent(link.userInfo)
        val colon = info.indexOf(':')
        require(colon > 0) { "tuic без uuid/пароля" }
        return JSONObject()
            .put("type", "tuic")
            .put("tag", link.name)
            .put("server", link.host)
            .put("server_port", link.port)
            .put("uuid", info.substring(0, colon))
            .put("password", info.substring(colon + 1))
            .put("congestion_control", link.query["congestion_control"] ?: "bbr")
            .put("tls", defaultTls(link).put("alpn", JSONArray(listOf("h3"))))
    }

    // ─────────────────────────── общие куски ───────────────────────────

    private fun defaultTls(link: Link): JSONObject {
        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", link.query["sni"] ?: link.query["peer"] ?: link.host)
        if (link.query["insecure"] == "1" || link.query["allowInsecure"] == "1") {
            tls.put("insecure", true)
        }
        link.query["alpn"]?.takeIf { it.isNotBlank() }
            ?.let { tls.put("alpn", JSONArray(it.split(","))) }
        return tls
    }

    private fun tlsFor(link: Link, security: String): JSONObject? {
        if (security.isBlank() || security == "none") return null
        val tls = defaultTls(link)
        link.query["fp"]?.takeIf { it.isNotBlank() }
            ?.let { tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
        if (security == "reality") {
            val pbk = link.query["pbk"].orEmpty()
            require(pbk.isNotBlank()) { "reality без публичного ключа" }
            tls.put(
                "reality",
                JSONObject()
                    .put("enabled", true)
                    .put("public_key", pbk)
                    .put("short_id", link.query["sid"].orEmpty()),
            )
        }
        return tls
    }

    private fun transportFor(link: Link): JSONObject? = when (link.query["type"]?.lowercase()) {
        "ws" -> JSONObject().put("type", "ws").apply {
            (link.query["path"] ?: link.path.takeIf { it.isNotBlank() })?.let { put("path", it) }
            link.query["host"]?.takeIf { it.isNotBlank() }
                ?.let { put("headers", JSONObject().put("Host", it)) }
        }
        "grpc" -> JSONObject().put("type", "grpc")
            .put("service_name", link.query["serviceName"].orEmpty())
        "http", "h2" -> JSONObject().put("type", "http").apply {
            (link.query["path"] ?: link.path.takeIf { it.isNotBlank() })?.let { put("path", it) }
            link.query["host"]?.takeIf { it.isNotBlank() }?.let { put("host", JSONArray(it.split(","))) }
        }
        // tcp и пустое значение — транспорт по умолчанию, секция не нужна.
        else -> null
    }

    private fun decodeBase64Plain(text: String): String? =
        decodeBase64(text) { it.contains(':') }

    // ─────────────────────────── сборка конфига ───────────────────────────

    /**
     * Собираем ту же форму, что генерирует `sub.php`: тот же селектор, тот же urltest, та же
     * закалка. Так чужая подписка получает ровно те же гарантии, что и наша, — включая отбой
     * IPv6, без которого висли Telegram и другие приложения.
     */
    private fun build(outbounds: List<JSONObject>): String {
        val tags = JSONArray(outbounds.map { it.getString("tag") })

        val selector = JSONObject()
            .put("type", "selector")
            .put("tag", SELECTOR_TAG)
            .put("outbounds", JSONArray(listOf(AUTO_TAG) + outbounds.map { it.getString("tag") }))
            .put("default", AUTO_TAG)
            .put("interrupt_exist_connections", true)

        val urltest = JSONObject()
            .put("type", "urltest")
            .put("tag", AUTO_TAG)
            .put("outbounds", tags)
            .put("url", PROBE_URL)
            .put("interval", "1m")
            .put("tolerance", 150)
            .put("interrupt_exist_connections", true)

        val all = JSONArray()
        all.put(selector)
        all.put(urltest)
        outbounds.forEach { all.put(it) }
        all.put(JSONObject().put("type", "direct").put("tag", "direct"))

        val dns = JSONObject()
            .put(
                "servers",
                JSONArray(
                    listOf(
                        // udp, а не DoT: постоянное TLS-соединение внутри туннеля подвисает,
                        // и резолв встаёт целиком при живом туннеле.
                        JSONObject().put("tag", "dns-remote").put("type", "udp")
                            .put("server", "1.1.1.1").put("detour", SELECTOR_TAG),
                        JSONObject().put("tag", "dns-local").put("type", "local"),
                    ),
                ),
            )
            .put("final", "dns-remote")
            // ipv4_only, а не prefer_ipv4: IPv6 наружу всё равно отбивается правилом ниже,
            // поэтому AAAA-запросы — лишняя половина каждого резолва со своим шансом на таймаут.
            .put("strategy", "ipv4_only")

        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            // IPv6-адрес обязателен: без него IPv6-трафик не заходит в туннель и утекает
            // с настоящего адреса. Наружу он не пойдёт — его отбивает правило ниже.
            .put("address", JSONArray(listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126")))
            .put("auto_route", true)
            .put("strict_route", true)
            .put("stack", "system")

        val route = JSONObject()
            .put("auto_detect_interface", true)
            .put("default_domain_resolver", "dns-local")
            .put("final", SELECTOR_TAG)
            .put(
                "rules",
                JSONArray(
                    listOf(
                        JSONObject().put("action", "sniff"),
                        JSONObject().put("protocol", "dns").put("action", "hijack-dns"),
                        JSONObject().put("ip_version", 6).put("action", "reject")
                            .put("method", "default"),
                    ),
                ),
            )

        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", dns)
            .put("inbounds", JSONArray(listOf(tun)))
            .put("outbounds", all)
            .put("route", route)
            .toString(2)
    }

    /** vmess-ссылки разбираются до общего парсера — там base64 от JSON, а не URI. */
    private fun vmessOutbound(uri: String): JSONObject? {
        val payload = uri.substring("vmess://".length).substringBefore('#')
        val json = decodeBase64(payload) { it.trimStart().startsWith("{") } ?: return null
        return runCatching { vmessFromJson(json) }.getOrNull()
    }
}
