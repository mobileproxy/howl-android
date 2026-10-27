package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.compose.component.connectionHealthText
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.health.HealthRuntime
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServiceNotification(private val status: MutableLiveData<Status>, private val service: Service) :
    BroadcastReceiver(),
    CommandClient.Handler {
    companion object {
        private const val notificationId = 1
        private const val notificationChannel = "service"
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        fun checkPermission(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return true
            }
            return Application.notification.areNotificationsEnabled()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private val commandClient =
        CommandClient(GlobalScope, CommandClient.ConnectionType.Status, this, localOnly = true)
    private var receiverRegistered = false
    @Volatile private var notificationScope: CoroutineScope? = null
    private var servicePhase = Status.Stopped
    private var traffic: String? = null

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(service, notificationChannel).setShowWhen(false).setOngoing(true)
            .setContentTitle("Howl").setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_menu)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(
                        service,
                        MainActivity::class.java,
                    ).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    flags,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_LOW).apply {
                addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        service.getText(R.string.stop),
                        PendingIntent.getBroadcast(
                            service,
                            0,
                            Intent(Action.SERVICE_CLOSE).setPackage(service.packageName),
                            flags,
                        ),
                    ).build(),
                )
            }
    }

    fun show(lastProfileName: String, @StringRes contentTextId: Int) {
        // Preserve the existing show API while keeping lifecycle and probe evidence separate.
        servicePhase = when (contentTextId) {
            R.string.status_starting -> Status.Starting
            R.string.status_started -> Status.Started
            else -> status.value ?: Status.Stopped
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Application.notification.createNotificationChannel(
                NotificationChannel(
                    notificationChannel,
                    "Service Notifications",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        service.startForeground(
            notificationId,
            notificationBuilder
                .setContentTitle(lastProfileName.takeIf { it.isNotBlank() } ?: "Howl")
                .apply { updateHealthText() }.build(),
        )
        if (notificationScope == null) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            notificationScope = scope
            // Health is always visible, including when speed notifications are disabled.
            // HealthMonitor owns a single expiry timer; this collector never polls.
            scope.launch {
                HealthRuntime.state.collect { refresh() }
            }
        }
    }

    suspend fun start() {
        if (Settings.dynamicNotification && checkPermission()) {
            commandClient.connect()
            withContext(Dispatchers.Main) {
                registerReceiver()
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        service.registerReceiver(
            this,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        receiverRegistered = true
    }

    override fun updateStatus(status: StatusMessage) {
        val content =
            Libbox.formatBytes(status.uplink) + "/s ↑\t" + Libbox.formatBytes(status.downlink) + "/s ↓"
        notificationScope?.launch {
            traffic = content
            refresh()
        }
    }

    private fun updateHealthText() {
        val health = connectionHealthText(servicePhase, HealthRuntime.view(), Settings.watchdogEnabled)
        val label = service.getString(health.label)
        val details = health.hints.joinToString("\n") { service.getString(it) }
        notificationBuilder
            .setContentText(label)
            .setSubText(traffic)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$label\n$details"))
    }

    private fun refresh() {
        if (notificationScope == null || !checkPermission()) return
        updateHealthText()
        Application.notificationManager.notify(
            notificationId,
            notificationBuilder.build(),
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                commandClient.connect()
            }

            Intent.ACTION_SCREEN_OFF -> {
                commandClient.disconnect()
            }
        }
    }

    fun close() {
        val scope = notificationScope
        notificationScope = null
        scope?.cancel()
        traffic = null
        servicePhase = Status.Stopped
        commandClient.disconnect()
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (receiverRegistered) {
            service.unregisterReceiver(this)
            receiverRegistered = false
        }
    }
}
