package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.compose.screen.profileoverride.PerAppProxyScanner
import io.nekohasekai.sfa.database.Settings

/**
 * Включение/выключение «Режима Россия» одним вызовом — из настроек и из вопроса при первом запуске.
 *
 * Режим состоит из двух слоёв, и оба должны переключаться вместе, иначе получится полурабочее
 * состояние (домены идут напрямую, а банковское приложение — через VPN, и перевод не проходит):
 *   • домены — правило в конфиге, применяется при сборке (см. [RussiaMode.apply]);
 *   • приложения — управляемый список per-app обхода.
 *
 * Свой список приложений пользователя НЕ трогаем: управляемый режим хранится отдельной парой
 * ключей, поэтому при выключении режима прежние ручные настройки возвращаются сами.
 */
object RussiaModeController {

    suspend fun setEnabled(enabled: Boolean) {
        Settings.russiaModeEnabled = enabled
        Settings.russiaModeAsked = true
        if (enabled) {
            Settings.perAppProxyEnabled = true
            Settings.perAppProxyManagedMode = true
            // Не смогли перечислить пакеты (нужен Shizuku на части устройств) — не беда:
            // доменный слой всё равно работает, список просто останется пустым.
            Settings.perAppProxyManagedList = runCatching {
                PerAppProxyScanner.scanAllRussianApps()
            }.getOrDefault(emptySet())
        } else {
            Settings.perAppProxyManagedMode = false
        }
    }
}
