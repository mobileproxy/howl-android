package io.nekohasekai.sfa.ktx

import android.content.Context
import io.nekohasekai.sfa.R

/**
 * Превращает технический текст ошибки импорта сервера в понятное пользователю сообщение.
 *
 * Импорт-хендлер и ядро sing-box отдают сырые строки (IOException, «failed to decode»,
 * «unable to resolve host» и т.п.) — для массового VPN-приложения это отпугивает. Здесь мы
 * распознаём частые случаи и заменяем их человеческой фразой. Неизвестные случаи возвращаются
 * КАК ЕСТЬ — то есть не хуже прежнего поведения.
 */
fun Context.friendlyImportError(raw: String?): String {
    val r = raw?.lowercase().orEmpty()
    return when {
        r.isBlank() -> getString(R.string.error_generic)

        // Сеть/загрузка подписки по ссылке.
        r.contains("resolve host") || r.contains("failed to connect") ||
            r.contains("timeout") || r.contains("timed out") ||
            r.contains("unreachable") || r.contains("network is") ||
            r.contains("no address associated") || r.contains("connection refused") ||
            r.contains("unable to resolve") || r.contains("connect timed out") ->
            getString(R.string.error_check_connection)

        // Содержимое не распозналось как сервер/подписка/конфиг.
        r.contains("decode") || r.contains("parse") || r.contains("invalid") ||
            r.contains("unexpected") || r.contains("json") || r.contains("not a valid") ||
            r.contains("unsupported") || r.contains("no outbound") || r.contains("malformed") ->
            getString(R.string.error_not_a_server)

        else -> raw ?: getString(R.string.error_generic)
    }
}
