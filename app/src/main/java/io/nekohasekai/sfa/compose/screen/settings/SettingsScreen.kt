package io.nekohasekai.sfa.compose.screen.settings

import android.content.Context
import android.os.PowerManager
import android.text.format.DateFormat
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.navigation.ProfileRoutes
import io.nekohasekai.sfa.compose.screen.dashboard.DashboardViewModel
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.update.UpdateCheckException
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.utils.DnsOverride
import io.nekohasekai.sfa.utils.HookModuleUpdateNotifier
import io.nekohasekai.sfa.utils.HookStatusClient
import io.nekohasekai.sfa.utils.RussiaModeController
import io.nekohasekai.sfa.utils.SplitTunnel
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Настройки — общая с Windows схема (см. [HowlSettingsMenu]). Экран только собирает состояние
 * из настроек и выполняет действия; как это выглядит, решает общий компонент.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(title = { Text(stringResource(R.string.title_settings)) })
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hookStatus by HookStatusClient.status.collectAsState()
    val updateInfo by UpdateState.updateInfo
    val isChecking by UpdateState.isChecking

    // Значения, которые меняются на под-экранах, перечитываем при возврате на меню.
    var refresh by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(Unit) { HookStatusClient.refresh() }

    var watchdog by remember { mutableStateOf(Settings.watchdogEnabled) }
    var autostart by remember { mutableStateOf(Settings.autoStartOnBoot) }
    var autoconnect by remember { mutableStateOf(Settings.autoConnectOnAppOpen) }
    var russia by remember { mutableStateOf(Settings.russiaModeEnabled) }
    var subscriptionSummary by remember { mutableStateOf("") }
    var updateStatus by remember { mutableStateOf<String?>(null) }

    val backgroundOk = remember(refresh) { batteryUnrestricted(context) }
    val splitSummary = remember(refresh) { splitSummary(context) }
    val appsSummary = remember(refresh, russia) { appsSummary(context) }
    val dnsSummary = remember(refresh) { dnsSummary(context) }
    val languages = remember {
        getSupportedLocales(context).map { locale ->
            locale.toLanguageTag() to locale.getDisplayName(locale)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
    }
    val languageTag = remember(refresh) {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        if (appLocales.isEmpty) "" else appLocales.toLanguageTags()
    }
    val noSubscription = stringResource(R.string.menu_subscription_none)
    LaunchedEffect(refresh) {
        subscriptionSummary = withContext(Dispatchers.IO) { subscriptionSummary(context) } ?: noSubscription
    }

    // Загрузка обновления — тем же путём, что окно «Доступно обновление» в MainActivity.
    var showDownloadDialog by remember { mutableStateOf(false) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    val info = updateInfo
    val state = HowlMenuState(
        watchdog = watchdog,
        autostartOnBoot = autostart,
        autoconnectOnOpen = autoconnect,
        backgroundOk = backgroundOk,
        russia = russia,
        splitSummary = splitSummary,
        appsSummary = appsSummary,
        dnsSummary = dnsSummary,
        subscriptionSummary = subscriptionSummary,
        languages = languages,
        languageTag = languageTag,
        update = HowlUpdateRowState(
            versionText = stringResource(R.string.menu_update_version, BuildConfig.VERSION_NAME),
            availableVersion = info?.versionName,
            availableSizeMb = (info?.fileSize ?: 0L) / 1024 / 1024,
            notes = info?.releaseNotes,
            checking = isChecking,
            status = updateStatus,
        ),
        showPrivilege = hookStatus != null,
        privilegeBadge = HookModuleUpdateNotifier.isDowngrade(hookStatus) || HookModuleUpdateNotifier.isUpgrade(hookStatus),
    )

    // Лист «Добавить сервер» живёт на главной (там же QR, ссылка, файл) — его модель общая на
    // всё приложение, поэтому берём модель активности, а не экрана настроек.
    val activity = context as? ComponentActivity
    val dashboardViewModel: DashboardViewModel? = activity?.let { viewModel<DashboardViewModel>(it) }

    val actions = HowlMenuActions(
        onWatchdog = { checked ->
            watchdog = checked
            scope.launch(Dispatchers.IO) { Settings.watchdogEnabled = checked }
        },
        onKillSwitch = { navController.navigate("settings/kill_switch") },
        onAutostart = { checked ->
            autostart = checked
            scope.launch(Dispatchers.IO) { Settings.autoStartOnBoot = checked }
        },
        onAutoconnect = { checked ->
            autoconnect = checked
            scope.launch(Dispatchers.IO) { Settings.autoConnectOnAppOpen = checked }
        },
        onBackground = { navController.navigate("settings/background_work") },
        onRussia = { checked ->
            russia = checked
            scope.launch(Dispatchers.IO) {
                RussiaModeController.setEnabled(checked)
                runCatching { Libbox.newStandaloneCommandClient().serviceReload() }
                withContext(Dispatchers.Main) { refresh++ }
            }
        },
        onSplit = { navController.navigate("settings/profile_override/split_tunnel") },
        onApps = { navController.navigate("settings/profile_override") },
        onDns = { navController.navigate("settings/dns") },
        onSubscription = {
            scope.launch {
                val id = Settings.selectedProfile
                val exists = id != -1L && withContext(Dispatchers.IO) { ProfileManager.get(id) } != null
                if (exists) navController.navigate(ProfileRoutes.editProfile(id))
                else openAddServer(navController, dashboardViewModel)
            }
        },
        onAddServer = { openAddServer(navController, dashboardViewModel) },
        onLanguage = { tag ->
            val list = if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
            AppCompatDelegate.setApplicationLocales(list)
            refresh++
        },
        onCheckUpdate = {
            scope.launch {
                UpdateState.isChecking.value = true
                updateStatus = null
                val message = withContext(Dispatchers.IO) {
                    try {
                        val result = Vendor.checkUpdateAsync()
                        UpdateState.setUpdate(result)
                        if (result == null) context.getString(R.string.menu_update_latest) else null
                    } catch (_: UpdateCheckException.TrackNotSupported) {
                        UpdateState.setUpdate(null)
                        context.getString(R.string.update_track_not_supported)
                    } catch (e: Exception) {
                        Log.e("SettingsScreen", "checkUpdateAsync failed", e)
                        UpdateState.setUpdate(null)
                        e.message
                    }
                }
                updateStatus = message
                UpdateState.isChecking.value = false
            }
        },
        onInstallUpdate = {
            val url = UpdateState.updateInfo.value?.downloadUrl
            if (url != null) {
                showDownloadDialog = true
                downloadError = null
                downloadJob = scope.launch {
                    try {
                        withContext(Dispatchers.IO) { Vendor.downloadAndInstall(context, url) }
                        showDownloadDialog = false
                    } catch (e: Exception) {
                        downloadError = e.message
                    }
                }
            }
        },
        onAppMore = { navController.navigate("settings/app") },
        onDiagnostics = { navController.navigate("settings/watchdog") },
        onPrivilege = { navController.navigate("settings/privilege") },
    )

    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update)) },
            text = {
                Column {
                    val error = downloadError
                    if (error != null) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                    } else {
                        val progress by UpdateState.downloadProgress
                        val value = progress
                        Text(
                            if (value != null) {
                                "${stringResource(R.string.downloading)} ${(value * 100).toInt()}%"
                            } else {
                                stringResource(R.string.downloading)
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        if (value != null) {
                            LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    downloadJob?.cancel()
                    downloadJob = null
                    showDownloadDialog = false
                    downloadError = null
                    UpdateState.downloadProgress.value = null
                }) {
                    Text(stringResource(if (downloadError != null) R.string.ok else android.R.string.cancel))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState()),
    ) {
        HowlSettingsMenu(state, actions)
        // Запас снизу под плавающую строку состояния («Запущена · соединения · локации · таймер»),
        // которая рисуется поверх контента. Без него последний пункт меню не долистывался.
        Spacer(modifier = Modifier.height(96.dp))
    }
}

/** «Добавить сервер»: лист живёт на главной — переходим туда и открываем его. */
private fun openAddServer(navController: NavController, dashboardViewModel: DashboardViewModel?) {
    navController.navigate("dashboard") {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    dashboardViewModel?.showAddProfileSheet()
}

private fun batteryUnrestricted(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return pm?.isIgnoringBatteryOptimizations(context.packageName) == true
}

/** Первые два домена и «+N» — как подпись строки в Windows. */
private fun splitSummary(context: Context): String {
    val domains = SplitTunnel.parseDomains(Settings.splitTunnelDomains)
    if (domains.isEmpty()) return context.getString(R.string.menu_split_none)
    val head = domains.take(2).joinToString(", ")
    return if (domains.size > 2) "$head +${domains.size - 2}" else head
}

private fun appsSummary(context: Context): String =
    if (Settings.perAppProxyEnabled) {
        context.getString(R.string.menu_apps_on, Settings.getEffectivePerAppProxyList().size)
    } else {
        context.getString(R.string.menu_apps_off)
    }

private fun dnsSummary(context: Context): String = when (Settings.dnsMode) {
    DnsOverride.MODE_CLOUDFLARE -> context.getString(R.string.dns_mode_cloudflare)
    DnsOverride.MODE_GOOGLE -> context.getString(R.string.dns_mode_google)
    DnsOverride.MODE_ADGUARD -> context.getString(R.string.dns_mode_adguard)
    DnsOverride.MODE_CUSTOM -> {
        val ip = Settings.dnsCustomServer.trim()
        val label = context.getString(R.string.dns_mode_custom)
        if (ip.isEmpty()) label else "$label: $ip"
    }
    else -> context.getString(R.string.dns_mode_auto)
}

/** «Последнее обновление: 04.10 21:36» у подписки, имя профиля у локального, null — профиля нет. */
private suspend fun subscriptionSummary(context: Context): String? {
    val id = Settings.selectedProfile
    if (id == -1L) return null
    val profile = ProfileManager.get(id) ?: return null
    val typed = profile.typed
    if (typed.type != TypedProfile.Type.Remote || typed.lastUpdated.time <= 0L) return profile.name
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "ddMM HHmm")
    val stamp = java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(typed.lastUpdated)
    return context.getString(R.string.menu_subscription_updated, stamp)
}
