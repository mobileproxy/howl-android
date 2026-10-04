package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R

/**
 * Меню настроек Howl — ТА ЖЕ схема и тот же вид, что у Windows-клиента
 * (Howl-Windows: MainWindow.xaml, раздел «Настройки»; решение владельца 26.09 и 04.10.2026):
 * четыре раздела «Подключение · Маршрутизация · Профиль · Приложение», переключатели прямо в
 * строке, язык и обновление — в строке, отдельные экраны только там, где правда есть что
 * показать. Функции, которых на Windows нет (работа в фоне, приложения в обход VPN,
 * уведомления), встроены в те же разделы.
 *
 * Компонент НЕ читает настройки сам: всё приходит в [HowlMenuState], действия уходят в
 * [HowlMenuActions]. Так его рисует скриншот-тест в CI без телефона и без ядра.
 */
object HowlMenuColors {
    // Значения — из App.xaml Windows-клиента, чтобы два приложения не расходились в оттенках.
    val Card = Color(0xFF121826)
    val CardHigh = Color(0xFF1A2231)
    val Divider = Color(0xFF1C2533)
    val Border = Color(0xFF2A3550)
    val Mint = Color(0xFF19E3B1)
    val Ink = Color(0xFF0A0E14)
    val Text = Color(0xFFF2F5F9)
    val Muted = Color(0xFF8B94A3)
}

data class HowlUpdateRowState(
    /** «Howl для Android 1.0.0 (815)» — когда обновления нет. */
    val versionText: String,
    /** Найденная версия (имя) или null. */
    val availableVersion: String? = null,
    /** Размер найденной версии в МБ, 0 — неизвестен. */
    val availableSizeMb: Long = 0,
    /** Что нового — отдельным абзацем на всю ширину. */
    val notes: String? = null,
    val checking: Boolean = false,
    /** Итог ручной проверки («Установлена последняя версия») или ошибка. */
    val status: String? = null,
)

data class HowlMenuState(
    val watchdog: Boolean,
    val autostartOnBoot: Boolean,
    val autoconnectOnOpen: Boolean,
    val backgroundOk: Boolean,
    val russia: Boolean,
    val splitSummary: String,
    val appsSummary: String,
    val dnsSummary: String,
    val subscriptionSummary: String,
    /** Пары «тег языка → название»; пустой тег — «как в системе». */
    val languages: List<Pair<String, String>>,
    val languageTag: String,
    val update: HowlUpdateRowState,
    val showPrivilege: Boolean = false,
    val privilegeBadge: Boolean = false,
)

class HowlMenuActions(
    val onWatchdog: (Boolean) -> Unit = {},
    val onKillSwitch: () -> Unit = {},
    val onAutostart: (Boolean) -> Unit = {},
    val onAutoconnect: (Boolean) -> Unit = {},
    val onBackground: () -> Unit = {},
    val onRussia: (Boolean) -> Unit = {},
    val onSplit: () -> Unit = {},
    val onApps: () -> Unit = {},
    val onDns: () -> Unit = {},
    val onSubscription: () -> Unit = {},
    val onAddServer: () -> Unit = {},
    val onLanguage: (String) -> Unit = {},
    val onCheckUpdate: () -> Unit = {},
    val onInstallUpdate: () -> Unit = {},
    val onAppMore: () -> Unit = {},
    val onDiagnostics: () -> Unit = {},
    val onPrivilege: () -> Unit = {},
)

@Composable
fun HowlSettingsMenu(state: HowlMenuState, actions: HowlMenuActions, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(bottom = 20.dp)) {
        HowlMenuSection(stringResource(R.string.menu_sec_connection))
        HowlMenuCard {
            HowlToggleRow(
                Icons.Outlined.HealthAndSafety, stringResource(R.string.menu_watchdog),
                stringResource(R.string.menu_watchdog_sub), state.watchdog, actions.onWatchdog,
            )
            HowlMenuDivider()
            HowlNavRow(
                Icons.Outlined.Security, stringResource(R.string.menu_kill_switch),
                stringResource(R.string.menu_kill_switch_sub), actions.onKillSwitch,
            )
            HowlMenuDivider()
            HowlToggleRow(
                Icons.Outlined.PowerSettingsNew, stringResource(R.string.menu_autostart),
                stringResource(R.string.menu_autostart_sub), state.autostartOnBoot, actions.onAutostart,
            )
            HowlMenuDivider()
            HowlToggleRow(
                Icons.Outlined.PlayCircleOutline, stringResource(R.string.menu_autoconnect),
                stringResource(R.string.menu_autoconnect_sub), state.autoconnectOnOpen, actions.onAutoconnect,
            )
            HowlMenuDivider()
            HowlNavRow(
                Icons.Outlined.BatteryChargingFull, stringResource(R.string.menu_background),
                stringResource(if (state.backgroundOk) R.string.menu_background_ok else R.string.menu_background_bad),
                actions.onBackground,
                subtitleColor = if (state.backgroundOk) HowlMenuColors.Muted else Color(0xFFFFB74D),
            )
        }

        HowlMenuSection(stringResource(R.string.menu_sec_routing))
        HowlMenuCard {
            HowlToggleRow(
                Icons.Outlined.Flag, stringResource(R.string.menu_russia),
                stringResource(R.string.menu_russia_sub), state.russia, actions.onRussia,
            )
            HowlMenuDivider()
            HowlNavRow(Icons.Outlined.AltRoute, stringResource(R.string.menu_split), state.splitSummary, actions.onSplit)
            HowlMenuDivider()
            HowlNavRow(Icons.Outlined.Apps, stringResource(R.string.menu_apps), state.appsSummary, actions.onApps)
            HowlMenuDivider()
            HowlNavRow(Icons.Outlined.Dns, stringResource(R.string.menu_dns), state.dnsSummary, actions.onDns)
        }

        HowlMenuSection(stringResource(R.string.menu_sec_profile))
        HowlMenuCard {
            HowlNavRow(
                Icons.Outlined.Link, stringResource(R.string.menu_subscription),
                state.subscriptionSummary, actions.onSubscription,
            )
            HowlMenuDivider()
            HowlNavRow(
                Icons.Outlined.Add, stringResource(R.string.menu_add_server),
                stringResource(R.string.menu_add_server_sub), actions.onAddServer,
            )
        }

        HowlMenuSection(stringResource(R.string.menu_sec_app))
        HowlMenuCard {
            HowlLanguageRow(state.languages, state.languageTag, actions.onLanguage)
            HowlMenuDivider()
            HowlUpdateRow(state.update, actions.onCheckUpdate, actions.onInstallUpdate)
            HowlMenuDivider()
            HowlNavRow(
                Icons.Outlined.Notifications, stringResource(R.string.menu_app_more),
                stringResource(R.string.menu_app_more_sub), actions.onAppMore,
            )
            HowlMenuDivider()
            HowlNavRow(
                Icons.Outlined.MonitorHeart, stringResource(R.string.menu_diagnostics),
                stringResource(R.string.menu_diagnostics_sub), actions.onDiagnostics,
            )
            if (state.showPrivilege) {
                HowlMenuDivider()
                HowlNavRow(
                    Icons.Outlined.AdminPanelSettings, stringResource(R.string.privilege_settings),
                    null, actions.onPrivilege, badge = state.privilegeBadge,
                )
            }
        }
    }
}

// ── Строительные блоки (общие с другими экранами Howl) ──────────────────────────────────

/** Заголовок раздела: мятный, полужирный, как SettingsSection в Windows. */
@Composable
fun HowlMenuSection(text: String) {
    Text(
        text = text,
        color = HowlMenuColors.Mint,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 8.dp),
    )
}

/** Карточка раздела: скругление 14, внутренний отступ 4 — строки сами скругляются внутри. */
@Composable
fun HowlMenuCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(HowlMenuColors.Card)
            .padding(4.dp),
    ) { content() }
}

/** Линия между строками — с отступом под текст, а не во всю ширину (как в Windows). */
@Composable
fun HowlMenuDivider() {
    Box(
        modifier = Modifier
            .padding(start = 52.dp, end = 12.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(HowlMenuColors.Divider),
    )
}

@Composable
private fun HowlRowFrame(
    onClick: (() -> Unit)?,
    role: Role? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val base = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
    Row(
        modifier = (if (onClick != null) base.clickable(role = role, onClick = onClick) else base)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun HowlRowIcon(icon: ImageVector, badge: Boolean = false) {
    Box(modifier = Modifier.width(40.dp)) {
        Icon(icon, contentDescription = null, tint = HowlMenuColors.Mint, modifier = Modifier.size(22.dp))
        if (badge) {
            Box(
                modifier = Modifier
                    .offset(x = 17.dp, y = (-2).dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(HowlMenuColors.Mint),
            )
        }
    }
}

@Composable
private fun HowlRowTexts(
    title: String,
    subtitle: String?,
    modifier: Modifier,
    subtitleColor: Color = HowlMenuColors.Muted,
) {
    Column(modifier = modifier) {
        Text(title, color = HowlMenuColors.Text, fontSize = 15.5.sp, lineHeight = 20.sp)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                color = subtitleColor,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
fun HowlNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    badge: Boolean = false,
    subtitleColor: Color = HowlMenuColors.Muted,
) {
    HowlRowFrame(onClick = onClick, role = Role.Button) {
        HowlRowIcon(icon, badge)
        HowlRowTexts(title, subtitle, Modifier.weight(1f), subtitleColor)
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = HowlMenuColors.Muted,
            modifier = Modifier
                .padding(start = 10.dp)
                .size(20.dp),
        )
    }
}

/** Строка с переключателем: нажатие по всей строке переключает (как в Windows). */
@Composable
fun HowlToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    HowlRowFrame(onClick = if (enabled) ({ onChange(!checked) }) else null, role = Role.Switch) {
        HowlRowIcon(icon)
        HowlRowTexts(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        HowlSwitch(checked = checked, enabled = enabled)
    }
}

/**
 * Переключатель Howl: дорожка 44×24, бегунок 16 — тот же, что ToggleSwitch в Windows.
 * Material-переключатель (52×32 с крупным бегунком) выглядел чужим рядом с остальным меню.
 */
@Composable
fun HowlSwitch(checked: Boolean, enabled: Boolean = true) {
    val x by animateDpAsState(if (checked) 23.dp else 3.dp, tween(150), label = "thumb")
    val track by animateColorAsState(if (checked) HowlMenuColors.Mint else HowlMenuColors.CardHigh, tween(150), label = "track")
    val border by animateColorAsState(if (checked) HowlMenuColors.Mint else HowlMenuColors.Border, tween(150), label = "border")
    val thumb = if (checked) HowlMenuColors.Ink else HowlMenuColors.Muted
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(track)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier else Modifier.background(Color(0x73000000))),
    ) {
        Box(
            modifier = Modifier
                .offset(x = x, y = 4.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(thumb),
        )
    }
}

/** Небольшая кнопка-«чип» (Проверить) — как ChipButton в Windows. */
@Composable
fun HowlChipButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(HowlMenuColors.CardHigh)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (enabled) HowlMenuColors.Text else HowlMenuColors.Muted, fontSize = 13.5.sp)
    }
}

/** Мятная кнопка действия (Установить) — как MintButton в Windows. */
@Composable
fun HowlMintButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(HowlMenuColors.Mint)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Text(text, color = HowlMenuColors.Ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Язык — выбор прямо в строке, без отдельного экрана и диалога. */
@Composable
private fun HowlLanguageRow(
    languages: List<Pair<String, String>>,
    currentTag: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = languages.firstOrNull { it.first == currentTag }?.second
        ?: stringResource(R.string.menu_language_system)
    HowlRowFrame(onClick = { open = true }, role = Role.DropdownList) {
        HowlRowIcon(Icons.Outlined.Translate)
        HowlRowTexts(stringResource(R.string.menu_language), null, Modifier.weight(1f))
        Box {
            Row(
                modifier = Modifier
                    .widthIn(min = 130.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(HowlMenuColors.CardHigh)
                    .border(1.dp, HowlMenuColors.Border, RoundedCornerShape(12.dp))
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    current,
                    color = HowlMenuColors.Text,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = HowlMenuColors.Muted,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(20.dp),
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_language_system)) },
                    onClick = {
                        open = false
                        onSelect("")
                    },
                )
                languages.forEach { (tag, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            open = false
                            onSelect(tag)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Обновление — в строке меню. Найдена версия: в подписи только «версия · размер», описание —
 * отдельным абзацем на всю ширину, кнопка «Установить» — под ним. На Windows 04.10.2026
 * описание шло в подпись, и широкая кнопка справа сжимала текст в столбик по слову.
 */
@Composable
private fun HowlUpdateRow(state: HowlUpdateRowState, onCheck: () -> Unit, onInstall: () -> Unit) {
    val available = state.availableVersion != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HowlRowIcon(Icons.Outlined.Download, badge = available)
            val subtitle = when {
                state.checking -> stringResource(R.string.menu_update_checking)
                available && state.availableSizeMb > 0 ->
                    stringResource(R.string.menu_update_available, state.availableVersion!!, state.availableSizeMb.toInt())
                available -> stringResource(R.string.menu_update_available_nosize, state.availableVersion!!)
                !state.status.isNullOrBlank() -> state.status
                else -> state.versionText
            }
            HowlRowTexts(stringResource(R.string.menu_update), subtitle, Modifier.weight(1f))
            if (!available) {
                Spacer(Modifier.width(12.dp))
                HowlChipButton(stringResource(R.string.menu_update_check), onCheck, enabled = !state.checking)
            }
        }
        if (available) {
            Column(modifier = Modifier.padding(start = 40.dp)) {
                if (!state.notes.isNullOrBlank()) {
                    Text(
                        state.notes.trim(),
                        color = HowlMenuColors.Muted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                HowlMintButton(stringResource(R.string.menu_update_install), onInstall)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
