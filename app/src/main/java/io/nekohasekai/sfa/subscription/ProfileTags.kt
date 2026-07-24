package io.nekohasekai.sfa.subscription

import org.json.JSONObject

/**
 * Теги групп текущего профиля: селектор («какой сервер выбран») и автоподбор (urltest).
 *
 * Зачем. Сторож умеет третью ступень починки — «сменить сервер»: увести на автоподбор и
 * перемерить задержки. Но теги он знал ЗАШИТЫМИ (`Howl` / `auto`) — так их называет наш
 * `sub.php`. У профиля от стороннего сервиса теги другие, и ступень молча ничего не делала:
 * ошибки не было, починки тоже.
 *
 * Читаем из самого конфига при старте службы — это дёшево (он и так уже разобран рядом) и
 * не требует ждать соединения с ядром, в отличие от подписки на группы.
 */
object ProfileTags {

    /** Группа выбора сервера. `null` — в профиле её нет, менять сервер нечем. */
    @Volatile
    var selector: String? = null
        private set

    /** Группа автоподбора по задержке. `null` — автоподбора в профиле нет. */
    @Volatile
    var auto: String? = null
        private set

    fun scan(content: String) {
        var foundSelector: String? = null
        var foundAuto: String? = null
        runCatching {
            val outbounds = JSONObject(content).optJSONArray("outbounds") ?: return@runCatching
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(i) ?: continue
                val tag = outbound.optString("tag").takeIf { it.isNotBlank() } ?: continue
                when (outbound.optString("type")) {
                    // Берём первые попавшиеся: в наших конфигах их по одному, а у чужих
                    // первый селектор — почти всегда и есть главный, его показывает интерфейс.
                    "selector" -> if (foundSelector == null) foundSelector = tag
                    "urltest" -> if (foundAuto == null) foundAuto = tag
                }
            }
        }
        selector = foundSelector
        auto = foundAuto
    }

    fun clear() {
        selector = null
        auto = null
    }
}
