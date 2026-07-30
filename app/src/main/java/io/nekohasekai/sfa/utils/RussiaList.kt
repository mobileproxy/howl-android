package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Списки «Режима Россия» — что считать российским. Три слоя, каждый следующий подстраховывает
 * предыдущий:
 *   1) СЕРВЕР — свежий список с gethowl.app: правится без выпуска новой версии приложения;
 *   2) КЭШ — последний удачно скачанный, лежит в файлах приложения: работает офлайн и когда
 *      сервер недоступен (в РФ это обычное дело);
 *   3) ВСТРОЕННЫЙ — зашит в сборку: работает всегда, даже на первом запуске без сети.
 *
 * Почему не удалённый `rule_set` прямо в конфиге ядра: он единая точка отказа — не скачался, и
 * ядро может не подняться вовсе. Здесь же неудача скачивания вообще ни на что не влияет: просто
 * продолжаем жить на кэше или встроенном списке.
 *
 * Формат файла (всё поля необязательные — чего нет, берётся из встроенного):
 * ```json
 * { "version": 1,
 *   "zone_suffixes": [".ru", ".su"],
 *   "domains": ["vk.com"],
 *   "app_prefixes": ["ru."],
 *   "app_packages": ["com.idamob.tinkoff.android"] }
 * ```
 */
object RussiaList {

    private const val URL_LIST = "https://gethowl.app/downloads/howl/ru-direct.json"
    private const val FILE_NAME = "ru-direct.json"
    private const val TIMEOUT_MS = 10_000

    // Чаще раза в сутки дёргать сервер незачем: список меняется редко.
    private const val MIN_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L

    // Защита от мусора: подменённый или битый файл не должен раздуть конфиг.
    private const val MAX_ENTRIES = 5_000

    data class Lists(
        val zoneSuffixes: List<String>,
        val domains: List<String>,
        val appPrefixes: List<String>,
        val appPackages: Set<String>,
    )

    /**
     * Встроенный список. Зоны целиком — это эквивалент «всего российского» и не требует
     * догонять новые сайты. С ведущей точкой: `domain_suffix` сравнивает окончание строки, и
     * суффикс без точки («ru») поймал бы заодно «peru».
     */
    private val BUNDLED = Lists(
        zoneSuffixes = listOf(".ru", ".su", ".xn--p1ai", ".moscow", ".tatar"),
        domains = listOf(
            // Российские сервисы на НЕ российских зонах + раздача контента: без неё у ВК и
            // Одноклассников не грузятся фото и видео.
            "vk.com", "vk.me", "userapi.com", "vk-cdn.net", "vkuservideo.net", "mycdn.me",
            "yandex.net", "yandex.com", "yastatic.net",
            "sberbank.com", "sberdevices.ru", "gosuslugi.ru", "tbank.ru", "t-bank.ru",
        ),
        // Подавляющее большинство российских приложений живёт на `ru.` — префикс покрывает и те,
        // что человек поставит позже, поэтому список не придётся догонять.
        appPrefixes = listOf("ru.", "com.yandex."),
        appPackages = setOf(
            "com.octopod.russianpost.client.android", // Почта России
            "com.idamob.tinkoff.android", // Т-Банк
            "com.gnivts.selfemployed", // Мой налог (ФНС)
            "com.vkontakte.android", // ВКонтакте
            "com.avito.android", // Авито
            "com.wildberries.ru", // Wildberries
            "com.dodopizza.app", // Додо Пицца
            "net.megafon.lk", // МегаФон
            "com.gazprombank.android.mobilebank.app", // Газпромбанк
            "com.sberbank.sbol", // Сбербанк (альтернативный пакет)
        ),
    )

    @Volatile
    private var active: Lists? = null

    private fun file(): File = File(Application.application.filesDir, FILE_NAME)

    /** Действующий список: кэш, если он есть и валиден, иначе встроенный. */
    fun current(): Lists {
        active?.let { return it }
        val loaded = runCatching { readCache() }.getOrNull() ?: BUNDLED
        active = loaded
        return loaded
    }

    private fun readCache(): Lists? {
        val cache = file()
        if (!cache.exists()) return null
        return parse(cache.readText())
    }

    /**
     * Разбор файла. Чего нет — берём из встроенного, поэтому сервер может присылать только
     * изменившуюся часть, а битое поле не обнуляет список.
     */
    private fun parse(text: String): Lists? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        fun strings(key: String): List<String>? {
            val array: JSONArray = root.optJSONArray(key) ?: return null
            val out = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotEmpty()) out.add(value)
                if (out.size >= MAX_ENTRIES) break
            }
            return out.takeIf { it.isNotEmpty() }
        }
        return Lists(
            zoneSuffixes = strings("zone_suffixes") ?: BUNDLED.zoneSuffixes,
            domains = strings("domains") ?: BUNDLED.domains,
            appPrefixes = strings("app_prefixes") ?: BUNDLED.appPrefixes,
            appPackages = strings("app_packages")?.toSet() ?: BUNDLED.appPackages,
        )
    }

    /**
     * Обновить список с сервера. Тихая операция: любая неудача (нет сети, 404, мусор в ответе)
     * оставляет всё как было. Зовётся при старте службы.
     */
    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        val cache = file()
        if (!force && cache.exists() &&
            System.currentTimeMillis() - cache.lastModified() < MIN_REFRESH_INTERVAL_MS
        ) {
            return@withContext
        }
        runCatching {
            val connection = (URL(URL_LIST).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            val body = try {
                if (connection.responseCode !in 200..299) return@runCatching
                connection.inputStream.bufferedReader().readText()
            } finally {
                connection.disconnect()
            }
            // Пишем и применяем только то, что реально разобралось, — иначе кэш испортился бы
            // страницей-заглушкой провайдера.
            val parsed = parse(body) ?: return@runCatching
            cache.writeText(body)
            active = parsed
        }
        Unit
    }
}
