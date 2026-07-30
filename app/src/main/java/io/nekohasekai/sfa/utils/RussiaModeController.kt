package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.compose.screen.profileoverride.PerAppProxyScanner
import io.nekohasekai.sfa.database.Settings

/**
 * Включение/выключение «Режима Россия» одним вызовом — из настроек и из вопроса при первом запуске.
 *
 * Режим состоит из двух слоёв, и оба переключаются вместе: полурежим хуже выключенного (домены
 * идут напрямую, а банковское приложение — через VPN, и перевод всё равно не проходит).
 *   • домены — правило в конфиге, применяется при сборке (см. [RussiaMode.apply]);
 *   • приложения — пакеты в списке per-app обхода.
 *
 * ★ Почему ОБЫЧНЫЙ список, а не «управляемый режим» апстрима (как сделано для Китая). Во-первых,
 * управляемый список НЕ ВИДЕН пользователю: экран «Управление» при нём блокируется, и человек не
 * может ни посмотреть, что уходит мимо VPN, ни поправить. Во-вторых, на экране «Маршрутизация»
 * живёт старая уборка, которая выключает управляемый режим при открытии экрана, — режим тихо
 * ломался бы ровно там, где стоит его переключатель. Обычный список решает оба: всё видно и
 * правится галочками, ничего не конфликтует.
 *
 * Свои отметки пользователя не трогаем: запоминаем в [Settings.russiaModeAddedApps], что добавили
 * именно мы, и при выключении убираем ровно это.
 */
object RussiaModeController {

    suspend fun setEnabled(enabled: Boolean) {
        Settings.russiaModeEnabled = enabled
        Settings.russiaModeAsked = true
        if (enabled) {
            // Не смогли перечислить пакеты (на части устройств нужен Shizuku) — не беда:
            // доменный слой работает в любом случае, список просто останется пустым.
            val russian = runCatching { PerAppProxyScanner.scanAllRussianApps() }
                .getOrDefault(emptySet())
            Settings.perAppProxyEnabled = true
            Settings.perAppProxyMode = Settings.PER_APP_PROXY_EXCLUDE
            Settings.perAppProxyList = Settings.perAppProxyList + russian
            Settings.russiaModeAddedApps = russian
        } else {
            val added = Settings.russiaModeAddedApps
            if (added.isNotEmpty()) {
                Settings.perAppProxyList = Settings.perAppProxyList - added
                Settings.russiaModeAddedApps = emptySet()
            }
        }
    }

    /**
     * Поставили новое приложение — если оно российское, добавляем в обход сразу. Иначе человеку
     * пришлось бы вспоминать про настройку каждый раз после установки нового банка.
     */
    suspend fun onAppInstalled(packageName: String, ownPackage: String) {
        if (!Settings.russiaModeEnabled) return
        if (!RussiaMode.isRussianApp(packageName, ownPackage)) return
        if (packageName in Settings.perAppProxyList) return
        Settings.perAppProxyList = Settings.perAppProxyList + packageName
        Settings.russiaModeAddedApps = Settings.russiaModeAddedApps + packageName
    }
}
