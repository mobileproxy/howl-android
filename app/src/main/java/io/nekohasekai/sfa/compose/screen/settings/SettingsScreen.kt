package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.SectionHeader
import io.nekohasekai.sfa.compose.theme.WarningOrange
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.utils.HookModuleUpdateNotifier
import io.nekohasekai.sfa.utils.HookStatusClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.title_settings)) },
        )
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasUpdate by UpdateState.hasUpdate
    val hookStatus by HookStatusClient.status.collectAsState()
    val hasPendingPrivilegeDowngrade = HookModuleUpdateNotifier.isDowngrade(hookStatus)
    val hasPendingPrivilegeUpdate = HookModuleUpdateNotifier.isUpgrade(hookStatus)

    // Раздел «Привилегированное расширение» показываем, только если хук LSPosed реально живёт
    // в system_server (status != null). Без root его там нет, а переключатели внутри всё равно
    // заблокированы — пункт только путал бы. Статус != null и когда модуль ждёт перезагрузки
    // после обновления, поэтому кнопка «Перезагрузить» остаётся достижимой.
    val hasPrivilegeModule = hookStatus != null
    LaunchedEffect(Unit) {
        HookStatusClient.refresh()
    }

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        SectionHeader(stringResource(R.string.settings_section_app))
        Card(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column {
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.title_app_settings),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (hasUpdate) {
                                Badge(containerColor = MaterialTheme.colorScheme.primary)
                            }
                            NavChevron()
                        }
                    },
                    modifier = Modifier.clickable { navController.navigate("settings/app") },
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )
            }
        }

        SectionHeader(stringResource(R.string.settings_section_connection))
        Card(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column {
                // «Ядро» убрано из меню: версия ядра, размер данных, рабочая директория,
                // бета-настройки и кнопка «Уничтожить» — отладка, а не пользовательские
                // настройки. Экран и код на месте, просто не на виду.
                //
                // Старый пункт «Работа в фоне» (settings/service) убран из меню: он показывал
                // только разрешение на фоновую работу, и это полностью перекрыто новым экраном
                // «Работа в фоне» (settings/background_work) — статус батареи, always-on VPN,
                // инструкция под производителя. Два одинаковых пункта в меню путали. Route и
                // экран ServiceSettingsScreen оставлены на месте, просто не на виду.

                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.watchdog),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.HealthAndSafety,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = { NavChevron() },
                    modifier = Modifier.clickable { navController.navigate("settings/watchdog") },
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )

                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.dns_settings),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = { NavChevron() },
                    modifier = Modifier.clickable { navController.navigate("settings/dns") },
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )

                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.auto_connect_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.PlayCircleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = { NavChevron() },
                    modifier = Modifier.clickable { navController.navigate("settings/auto_connect") },
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )

                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.background_work_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.BatteryChargingFull,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = { NavChevron() },
                    modifier = Modifier.clickable { navController.navigate("settings/background_work") },
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )

                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.kill_switch_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = { NavChevron() },
                    modifier = Modifier.clickable { navController.navigate("settings/kill_switch") },
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )
            }
        }

        SectionHeader(stringResource(R.string.settings_section_advanced))
        Card(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column {
                // Диагностика: логи ядра (экран есть, но убран из таб-бара — доступ отсюда).
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.title_log),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = { NavChevron() },
                    modifier = Modifier.clickable { navController.navigate("log") },
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )

                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.profile_override),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.FilterAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = { NavChevron() },
                    modifier = Modifier.clickable { navController.navigate("settings/profile_override") },
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )

                // «Удалённое управление» — фича апстрима: пульт к ЧУЖОМУ экземпляру sing-box
                // (свой роутер/VPS) по URL и secret. Нашим клиентам управлять нечем, поэтому
                // пункт убран из меню. Код фичи не тронут: она вросла в MainActivity и главный
                // экран через режим isRemote, вырезать её мимоходом — риск сломать подключение.

                if (hasPrivilegeModule) {
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.privilege_settings),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Outlined.AdminPanelSettings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (hasPendingPrivilegeDowngrade) {
                                    Badge(containerColor = MaterialTheme.colorScheme.error)
                                } else if (hasPendingPrivilegeUpdate) {
                                    Badge(containerColor = WarningOrange)
                                }
                                NavChevron()
                            }
                        },
                        modifier = Modifier.clickable { navController.navigate("settings/privilege") },
                        colors =
                        ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }

        // Запас снизу под плавающую строку состояния («Запущена · соединения · локации · таймер»),
        // которая рисуется поверх контента. Без него последний пункт меню не долистывался.
        Spacer(modifier = Modifier.height(96.dp))
    }
}

// Единый шеврон «›» для строк-настроек, которые ведут на отдельный экран. Раньше в главном меню
// настроек его не было, а на под-экранах был — подсказка «тапни → перейдёшь» выглядела случайной.
@Composable
private fun NavChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
