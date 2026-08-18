package io.nekohasekai.sfa.bg

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.AppEventLog
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Сторож САМОЙ СЛУЖБЫ: поднимает VPN, если оболочка телефона убила процесс.
 *
 * Зачем. `ConnectivityWatchdog` следит за связью, но живёт ВНУТРИ процесса и умирает вместе с ним.
 * Журнал 17–18.08 показал, к чему это приводит на OnePlus: три раза служба исчезала бесследно —
 * ни строки, даже от ядра, — и не поднималась, пока человек не замечал и не включал вручную.
 * Простой составил 717 минут из 1403, то есть больше половины суток. Энергосбережение при этом
 * было отключено: убивает не режим экономии, а сама оболочка.
 *
 * Как. Будильник `AlarmManager` живёт в системе, а не в нашем процессе, и приёмник объявлен в
 * манифесте — значит при срабатывании Android СОЗДАСТ процесс заново, даже если тот был убит.
 * Дальше смотрим на [BoxService.running]: в свежесозданном процессе он false по определению, и
 * это надёжный признак «нас убили». Поднимаем службу и пишем строку в журнал, чтобы в следующем
 * разборе было видно, сколько раз сработало воскрешение.
 *
 * Почему не только START_STICKY. Тот работает, когда система убивает службу штатно, но
 * агрессивные оболочки (OnePlus, Xiaomi, Huawei) умеют «замораживать» приложение так, что
 * перезапуска не происходит. Будильник — независимая вторая попытка.
 *
 * Ограничения, о которых честно стоит помнить: после «Остановить принудительно» в настройках
 * Android будильники приложения стираются и воскрешать некому; после перезагрузки телефона за
 * это отвечает [BootReceiver].
 */
object ServiceGuard {

    const val ACTION = "io.nekohasekai.sfa.SERVICE_GUARD"

    /** Как часто проверяем, жива ли служба. Реже — дольше простой, чаще — лишние пробуждения. */
    private const val INTERVAL_MS = 5 * 60_000L

    private val alarmManager: AlarmManager?
        get() = runCatching {
            Application.application.getSystemService(AlarmManager::class.java)
        }.getOrNull()

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        Application.application,
        1,
        Intent(ACTION).setPackage(Application.application.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Поставить следующую проверку. Зовётся при старте службы и после каждого срабатывания. */
    fun schedule() {
        val manager = alarmManager ?: return
        val at = System.currentTimeMillis() + INTERVAL_MS
        runCatching {
            // Неточного будильника достаточно: разница в минуту-другую здесь ничего не решает,
            // а точный на Android 12+ требует отдельного разрешения и лишний раз будит телефон.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent())
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, at, pendingIntent())
            }
        }
    }

    /** Снять проверку — при осознанном выключении VPN человеком. */
    fun cancel() {
        runCatching { alarmManager?.cancel(pendingIntent()) }
    }
}

/**
 * Приёмник будильника [ServiceGuard]. Объявлен в манифесте — переживает смерть процесса.
 */
class ServiceGuardReceiver : BroadcastReceiver() {

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ServiceGuard.ACTION) return
        // Следующую проверку ставим ВСЕГДА и ПЕРВЫМ делом: даже если ниже что-то пойдёт не так,
        // цепочка не должна оборваться — иначе воскрешать будет некому.
        ServiceGuard.schedule()

        // Служба жива в этом процессе — ничего не делаем. Именно этот флаг и отличает «работает»
        // от «процесс создан заново ради нашего же будильника».
        if (BoxService.running) return

        GlobalScope.launch(Dispatchers.IO) {
            // Человек выключил VPN сам — воскрешать нельзя, это было бы навязчиво.
            if (!Settings.startedByUser) {
                ServiceGuard.cancel()
                return@launch
            }
            AppEventLog.log("старт", "служба была убита системой — поднимаю заново")
            withContext(Dispatchers.Main) {
                runCatching { BoxService.start() }
                    .onFailure { AppEventLog.log("старт", "поднять не удалось: ${it.message}") }
            }
        }
    }
}
