package io.nekohasekai.sfa.bg

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import io.nekohasekai.sfa.Application

/**
 * Будильник сторожа: единственный способ проверять связь, пока телефон спит.
 *
 * Зачем. Фоновый цикл сторожа держался на корутинном `delay()`. Пока экран погашен, CPU уходит в
 * сон, и таймеры корутин ЗАМИРАЮТ вместе с ним — вызов просто не срабатывает вовремя. В журнале
 * 28–29.07 это видно прямо: между проверками сторожа 48 пауз по 5–65 минут вместо положенных 90
 * секунд (характерный пример — 09:15:49 → 10:06:13, 51 минута тишины при мёртвом туннеле). Всё это
 * время связи не было, а сторож оживал ровно тогда, когда человек включал экран (по триггеру
 * «экран включён»). Отсюда и ощущение «помогает только переподключение руками».
 *
 * Как. Следующую проверку планируем системным будильником `AlarmManager` с флагом
 * `AllowWhileIdle` — такой будильник система доставляет даже в Doze и БУДИТ устройство
 * (`RTC_WAKEUP`). Будильник одноразовый и переставляется после каждой проверки: так интервал
 * можно менять на ходу (при сбое проверяем чаще).
 *
 * Точность. `setExactAndAllowWhileIdle` с Android 12 требует разрешения на точные будильники;
 * если его нет — падаем на `setAndAllowWhileIdle` (неточный, система сама выберет ближайшее
 * удобное окно). Для нас это приемлемо: разница в десятки секунд, а не в десятки минут.
 * Если пользователь отключил энергосбережение для приложения (экран «Работа в фоне»), система
 * не троттлит такие будильники вовсе.
 *
 * WakeLock. На время самой проверки коротко удерживаем CPU: пробы занимают до ~16 секунд, и без
 * этого устройство успевает заснуть посреди пробы — она молча превращается в ложный «сбой».
 * Блокировка берётся с таймаутом и снимается сама: держать её дольше проверки незачем.
 */
object WatchdogAlarm {

    private const val ACTION = "io.nekohasekai.sfa.WATCHDOG_TICK"
    private const val WAKELOCK_TAG = "howl:watchdog"

    // С запасом на две пробы (по 8 с) + починку. Снимается автоматически по истечении.
    private const val WAKELOCK_TIMEOUT_MS = 40_000L

    private var receiver: BroadcastReceiver? = null

    @Volatile
    private var onTick: (() -> Unit)? = null

    private val alarmManager: AlarmManager?
        get() = runCatching {
            Application.application.getSystemService(AlarmManager::class.java)
        }.getOrNull()

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        Application.application,
        0,
        Intent(ACTION).setPackage(Application.application.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Включить будильник. [onTick] зовётся при каждом срабатывании (уже под удержанным CPU). */
    fun start(onTick: () -> Unit) {
        stop()
        this.onTick = onTick
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Держим CPU на время проверки. Снятия вручную нет намеренно: проверка уходит в
                // корутину и возвращает управление сразу, поэтому release() здесь снял бы
                // блокировку ДО пробы. Таймаут гарантирует, что она не залипнет.
                runCatching {
                    context.getSystemService(PowerManager::class.java)
                        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
                        ?.acquire(WAKELOCK_TIMEOUT_MS)
                }
                runCatching { this@WatchdogAlarm.onTick?.invoke() }
            }
        }
        receiver = newReceiver
        runCatching {
            ContextCompat.registerReceiver(
                Application.application,
                newReceiver,
                IntentFilter(ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    fun stop() {
        receiver?.let { runCatching { Application.application.unregisterReceiver(it) } }
        receiver = null
        onTick = null
        runCatching { alarmManager?.cancel(pendingIntent()) }
    }

    /** Запланировать следующую проверку через [delayMs]. Зовётся после каждой проверки. */
    fun schedule(delayMs: Long) {
        val manager = alarmManager ?: return
        val at = System.currentTimeMillis() + delayMs
        runCatching {
            val canBeExact =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
            if (canBeExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent())
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent())
            }
        }
    }
}
