package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import go.Seq
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.ktx.hasPermission
import io.nekohasekai.sfa.subscription.ConfigHardening
import io.nekohasekai.sfa.subscription.ProfileTags
import io.nekohasekai.sfa.utils.AppEventLog
import io.nekohasekai.sfa.utils.CoreLog
import io.nekohasekai.sfa.utils.DnsOverride
import io.nekohasekai.sfa.utils.RussiaList
import io.nekohasekai.sfa.utils.RussiaMode
import io.nekohasekai.sfa.utils.SplitTunnel
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class BoxService(private val service: Service, private val platformInterface: PlatformInterface) : CommandServerHandler {
    companion object {
        private const val PROFILE_UPDATE_INTERVAL = 15L * 60 * 1000 // 15 minutes in milliseconds
        private const val TAG = "BoxService"

        fun start() {
            val intent =
                runBlocking {
                    withContext(Dispatchers.IO) {
                        Intent(Application.application, Settings.serviceClass())
                    }
                }
            ContextCompat.startForegroundService(Application.application, intent)
        }

        fun stop() {
            Application.application.sendBroadcast(
                Intent(Action.SERVICE_CLOSE).setPackage(
                    Application.application.packageName,
                ),
            )
        }
    }

    var fileDescriptor: ParcelFileDescriptor? = null

    private val status = MutableLiveData(Status.Stopped)
    private val binder = ServiceBinder(status)
    private val notification = ServiceNotification(status, service)
    private lateinit var commandServer: CommandServer

    private var receiverRegistered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Action.SERVICE_CLOSE -> {
                        stopService()
                    }

                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            serviceUpdateIdleMode()
                        }
                    }
                }
            }
        }

    private fun startCommandServer() {
        val commandServer = CommandServer(this, platformInterface)
        commandServer.start()
        this.commandServer = commandServer
    }

    private var lastProfileName = ""

    private suspend fun startService() {
        try {
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            val selectedProfileId = Settings.selectedProfile
            if (selectedProfileId == -1L) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val profile = ProfileManager.get(selectedProfileId)
            if (profile == null) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val rawContent = File(profile.typed.path).readText()
            if (rawContent.isBlank()) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }
            // Домены-исключения подмешиваем на лету: файл профиля перезаписывается
            // при обновлении подписки, а локальный список должен это пережить.
            // ConfigHardening доводит ЛЮБОЙ профиль (в том числе чужой) до наших требований
            // по бесперебойности — иначе сторонняя подписка работала бы заметно хуже нашей.
            // Теги групп нужны сторожу для ступени «сменить сервер»: у чужого профиля они
            // называются иначе, чем у нашего, и зашивать их нельзя. scan внутри assembleConfig.
            val content = assembleConfig(rawContent)

            // Старт-событие с контекстом — чтобы в журнале сразу было видно версию, телефон и
            // какой профиль запущен. Это первое, что нужно при разборе присланного лога.
            AppEventLog.log(
                "старт",
                "Howl ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · " +
                    "профиль «${profile.name}» · автоподбор=${ProfileTags.auto ?: "нет"} · " +
                    // ★ Энергосбережение — ключ к «сторож молчал несколько часов»: без исключения
                    // система душит будильники, и фоновые проверки просто не выполняются. Раньше
                    // это приходилось только предполагать, теперь видно прямо в журнале.
                    "энергосбережение=${if (isIgnoringBatteryOptimizations()) "отключено (хорошо)" else "ВКЛЮЧЕНО (будильники душатся)"}",
            )

            lastProfileName = profile.name
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            DefaultNetworkMonitor.start()

            try {
                commandServer.startOrReloadService(
                    content,
                    OverrideOptions().apply {
                        autoRedirect = Settings.autoRedirect
                        if (Vendor.isPerAppProxyAvailable() && Settings.perAppProxyEnabled) {
                            val appList = Settings.getEffectivePerAppProxyList()
                            if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
                                includePackage =
                                    PlatformInterfaceWrapper.StringArray((appList + Application.application.packageName).iterator())
                            } else {
                                excludePackage =
                                    PlatformInterfaceWrapper.StringArray((appList - Application.application.packageName).iterator())
                            }
                        }
                    },
                )
            } catch (e: Exception) {
                stopAndAlert(Alert.CreateService, e.message)
                return
            }

            if (commandServer.needWIFIState()) {
                val wifiPermission =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    } else {
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    }
                if (!service.hasPermission(wifiPermission)) {
                    stopAndAlert(Alert.RequestLocationPermission)
                    return
                }
            }

            status.postValue(Status.Started)
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_started)
            }
            notification.start()
            // Ядро не замечает «подключено, но трафик не идёт» — за этим следит сторож.
            ConnectivityWatchdog.start { serviceReload0() }
            // Тихо подтягиваем свежий список «Режима Россия» (раз в сутки, неудача ни на что не
            // влияет — останемся на кэше или встроенном). Применится при следующей сборке конфига.
            if (Settings.russiaModeEnabled) {
                GlobalScope.launch(Dispatchers.IO) { runCatching { RussiaList.refresh() } }
            }
        } catch (e: Exception) {
            stopAndAlert(Alert.StartService, e.message)
            return
        }
    }

    /**
     * Единая сборка конфига перед запуском ядра. Вынесена в одно место НАМЕРЕННО: и первый
     * старт, и перезагрузка (serviceReload0) обязаны применять ОДНУ И ТУ ЖЕ цепочку, иначе
     * после reload часть настроек (закалка, DNS, теги для сторожа) молча пропадала бы до
     * перезапуска. Порядок: пользовательские правки (обход по доменам, выбор DNS) → закалка
     * (IPv6) → журнал ядра. Каждый шаг при сбое возвращает вход как есть.
     */
    private fun assembleConfig(rawContent: String): String {
        val content = CoreLog.apply(
            ConfigHardening.apply(
                DnsOverride.apply(
                    RussiaMode.apply(
                        SplitTunnel.apply(rawContent, Settings.splitTunnelDomains, Settings.splitTunnelMode),
                        Settings.russiaModeEnabled,
                    ),
                    Settings.dnsMode,
                    Settings.dnsCustomServer,
                ),
            ),
        )
        ProfileTags.scan(content)
        return content
    }

    override fun serviceStop() {
        // Конец работы службы в журнале раньше не отмечался: между «[старт]» и следующим
        // «[старт]» нельзя было понять, VPN работал всё это время или был выключён.
        // Формулировка отличается от штатного выключения (см. stopService) намеренно:
        // сюда приходит запрос ОТ ЯДРА, и в журнале эти два случая надо различать.
        AppEventLog.log("стоп", "остановлено ядром (перезагрузка конфигурации или сбой)")
        notification.close()
        status.postValue(Status.Starting)
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        closeService()
    }

    override fun serviceReload() {
        runBlocking {
            serviceReload0()
        }
    }

    suspend fun serviceReload0() {
        val selectedProfileId = Settings.selectedProfile
        if (selectedProfileId == -1L) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val profile = ProfileManager.get(selectedProfileId)
        if (profile == null) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val rawContent = File(profile.typed.path).readText()
        if (rawContent.isBlank()) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }
        // Та же сборка, что и при первом запуске: перезагрузка конфига не должна терять ни
        // закалку, ни выбор DNS, ни теги групп — иначе после reload сторож «слепнет».
        val content = assembleConfig(rawContent)
        lastProfileName = profile.name
        try {
            commandServer.startOrReloadService(
                content,
                OverrideOptions().apply {
                    autoRedirect = Settings.autoRedirect
                    if (Vendor.isPerAppProxyAvailable() && Settings.perAppProxyEnabled) {
                        val appList = Settings.getEffectivePerAppProxyList()
                        if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
                            includePackage = PlatformInterfaceWrapper.StringArray((appList + Application.application.packageName).iterator())
                        } else {
                            excludePackage = PlatformInterfaceWrapper.StringArray((appList - Application.application.packageName).iterator())
                        }
                    }
                },
            )
        } catch (e: Exception) {
            stopAndAlert(Alert.CreateService, e.message)
            return
        }

        if (commandServer.needWIFIState()) {
            val wifiPermission =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                } else {
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }
            if (!service.hasPermission(wifiPermission)) {
                stopAndAlert(Alert.RequestLocationPermission)
                return
            }
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? {
        val status = SystemProxyStatus()
        if (service is VPNService) {
            status.available = service.systemProxyAvailable
            status.enabled = service.systemProxyEnabled
        }
        return status
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        serviceReload()
    }

    /**
     * Исключено ли приложение из энергосбережения. Именно от этого зависит, будут ли работать
     * фоновые проверки сторожа: без исключения система откладывает будильники, и в журнале
     * появляются многочасовые паузы между проверками.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        Application.powerManager.isIgnoringBatteryOptimizations(Application.application.packageName)
    }.getOrDefault(false)

    @RequiresApi(Build.VERSION_CODES.M)
    private fun serviceUpdateIdleMode() {
        // ★ Вход/выход из Doze — в журнал. Это единственный способ отличить «сторож сломался» от
        // «система усыпила телефон и придержала будильники»: паузы между проверками должны
        // совпадать именно с окнами Doze.
        AppEventLog.log(
            "питание",
            if (Application.powerManager.isDeviceIdleMode) {
                "телефон уснул (Doze) — система придерживает фоновые проверки"
            } else {
                "телефон проснулся (вышли из Doze)"
            },
        )
        if (Application.powerManager.isDeviceIdleMode) {
            commandServer.pause()
        } else {
            commandServer.wake()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun stopService() {
        if (status.value != Status.Started) return
        // ★ ШТАТНАЯ остановка — это ИМЕННО ЭТОТ путь: кнопка в приложении, действие в шторке,
        // отзыв разрешения VPN системой. Событие «[стоп]» писалось только в serviceStop(),
        // куда приходит запрос ОТ ЯДРА, — а он при обычном выключении не срабатывает. Из-за
        // этого журнал 07–09.08 показал 30 запусков и НИ ОДНОЙ остановки, и отличить
        // «человек выключил VPN на ночь» от «система убила службу» было нечем: 43% времени
        // простоя остались без объяснения. Теперь у каждого выключения есть своя строка,
        // и молчание журнала однозначно значит «службу убили, она не прощалась».
        AppEventLog.log("стоп", "выключено из приложения или системой")
        status.value = Status.Stopping
        if (receiverRegistered) {
            service.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        notification.close()
        ConnectivityWatchdog.stop()
        GlobalScope.launch(Dispatchers.IO) {
            val pfd = fileDescriptor
            if (pfd != null) {
                pfd.close()
                fileDescriptor = null
            }
            DefaultNetworkMonitor.stop()
            closeService()
            commandServer.apply {
                close()
//                Seq.destroyRef(refnum)
            }
            Settings.startedByUser = false
            withContext(Dispatchers.Main) {
                status.value = Status.Stopped
                service.stopSelf()
            }
        }
    }

    private fun closeService() {
        runCatching {
            commandServer.closeService()
        }.onFailure {
            commandServer.setError("android: close service: ${it.message}")
        }
    }

    private suspend fun stopAndAlert(type: Alert, message: String? = null) {
        Settings.startedByUser = false
        ConnectivityWatchdog.stop()
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        DefaultNetworkMonitor.stop()
        if (::commandServer.isInitialized) {
            closeService()
            commandServer.close()
        }
        withContext(Dispatchers.Main) {
            if (receiverRegistered) {
                service.unregisterReceiver(receiver)
                receiverRegistered = false
            }
            notification.close()
            binder.broadcast { callback ->
                callback.onServiceAlert(type.ordinal, message)
            }
            status.value = Status.Stopped
            service.stopSelf()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("SameReturnValue")
    internal fun onStartCommand(): Int {
        if (status.value != Status.Stopped) return Service.START_NOT_STICKY
        status.value = Status.Starting

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                service,
                receiver,
                IntentFilter().apply {
                    addAction(Action.SERVICE_CLOSE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }

        GlobalScope.launch(Dispatchers.IO) {
            Settings.startedByUser = true
            try {
                startCommandServer()
            } catch (e: Exception) {
                stopAndAlert(Alert.StartCommandServer, e.message)
                return@launch
            }
            startService()
        }
        return Service.START_NOT_STICKY
    }

    internal fun onBind(): IBinder = binder

    internal fun onDestroy() {
        binder.close()
    }

    internal fun onRevoke() {
        stopService()
    }

    internal fun sendNotification(notification: Notification) {
        val builder =
            NotificationCompat.Builder(service, notification.identifier).setShowWhen(false)
                .setContentTitle(notification.title).setContentText(notification.body)
                .setOnlyAlertOnce(true).setSmallIcon(R.drawable.ic_menu)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
        if (!notification.subtitle.isNullOrBlank()) {
            builder.setContentInfo(notification.subtitle)
        }
        if (!notification.openURL.isNullOrBlank()) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(
                        service,
                        MainActivity::class.java,
                    ).apply {
                        setAction(Action.OPEN_URL).setData(Uri.parse(notification.openURL))
                        setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    },
                    ServiceNotification.flags,
                ),
            )
        }
        GlobalScope.launch(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Application.notification.createNotificationChannel(
                    NotificationChannel(
                        notification.identifier,
                        notification.typeName,
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            Application.notification.notify(notification.typeID, builder.build())
        }
    }

    override fun triggerNativeCrash() {
        Thread {
            Thread.sleep(200)
            throw RuntimeException("debug native crash")
        }.start()
    }

    override fun writeDebugMessage(message: String?) {
        Log.d("sing-box", message!!)
    }

    override fun connectSSHAgent(): Int = -1
}
