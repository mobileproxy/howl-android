package io.nekohasekai.sfa.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BootReceiver : BroadcastReceiver() {
    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
            }

            else -> return
        }
        GlobalScope.launch(Dispatchers.IO) {
            // startedByUser — VPN был запущен до перезагрузки; autoStartOnBoot — пользователь
            // разрешил поднимать его при загрузке (по умолчанию да, тумблер даёт отключить).
            if (Settings.startedByUser && Settings.autoStartOnBoot) {
                CrashReportManager.refresh()
                if (CrashReportManager.unreadCount.value > 0) {
                    Settings.startedByUser = false
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    BoxService.start()
                }
            }
        }
    }
}
