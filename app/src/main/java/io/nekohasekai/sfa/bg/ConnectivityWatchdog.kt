package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сторож соединения: ловит состояние «подключено, но трафик не идёт».
 *
 * Зачем нужен. Пинг в списке серверов НЕ доказывает, что туннель жив: во-первых, это последнее
 * удачное измерение, а не текущее; во-вторых, проба urltest идёт мимо локального DNS — имя
 * разрешает сам прокси-сервер. Поэтому зависшее DNS-соединение внутри туннеля (или мёртвые
 * сокеты после смены Wi-Fi↔LTE) выглядят как «всё зелёное», хотя ни один сайт не открывается.
 * Ядро такое не замечает и само не чинит.
 *
 * Что делает. Раз в минуту прогоняет ДВЕ разные пробы — так видно, что именно сломалось:
 *   • TCP на 1.1.1.1:443 — без DNS, проверяет сам туннель;
 *   • запрос к домену — проверяет DNS и путь целиком.
 * Трафик приложения идёт через тот же tun, что и у браузера, — это честная сквозная проверка.
 *
 * При двух провалах подряд перезагружает конфиг: соединения пересоздаются, как при ручном
 * переключении протокола. Каждое событие пишется в журнал (Настройки → Автопочинка).
 */
object ConnectivityWatchdog {

    private const val CHECK_INTERVAL_MS = 60_000L
    private const val FIRST_CHECK_DELAY_MS = 30_000L      // дать туннелю подняться
    private const val PROBE_TIMEOUT_MS = 8_000
    private const val FAILURES_BEFORE_HEAL = 2
    private const val MIN_HEAL_INTERVAL_MS = 3 * 60_000L  // защита от цикла перезагрузок
    private const val SETTLE_AFTER_HEAL_MS = 20_000L
    private const val MAX_LOG_LINES = 30

    private const val PROBE_IP = "1.1.1.1"
    private const val PROBE_PORT = 443
    private const val PROBE_URL = "https://www.gstatic.com/generate_204"
    private const val PROBE_HOST = "www.gstatic.com"

    private var scope: CoroutineScope? = null
    private var consecutiveFailures = 0
    private var lastHealAt = 0L

    fun start(heal: suspend () -> Unit) {
        stop()
        if (!Settings.watchdogEnabled) return
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope
        consecutiveFailures = 0
        newScope.launch { loop(heal) }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        consecutiveFailures = 0
    }

    private suspend fun loop(heal: suspend () -> Unit) {
        delay(FIRST_CHECK_DELAY_MS)
        val currentScope = scope ?: return
        while (currentScope.isActive) {
            runCatching { checkOnce(heal) }
            delay(CHECK_INTERVAL_MS)
        }
    }

    private suspend fun checkOnce(heal: suspend () -> Unit) {
        // Нет сети вообще (самолётный режим, нет сигнала) — не наша беда, чинить нечего.
        if (DefaultNetworkMonitor.defaultNetwork == null) {
            consecutiveFailures = 0
            return
        }

        val tunnelOk = probe { tcpReachable() }
        val domainOk = probe { domainReachable() }

        if (tunnelOk && domainOk) {
            if (consecutiveFailures > 0) {
                appendLog("связь восстановилась сама")
            }
            consecutiveFailures = 0
            return
        }

        consecutiveFailures++
        val what = when {
            !tunnelOk && !domainOk -> "туннель не отвечает"
            tunnelOk && !domainOk -> "DNS не отвечает (туннель жив)"
            else -> "домен открывается, прямой канал нет"
        }

        if (consecutiveFailures < FAILURES_BEFORE_HEAL) {
            appendLog("$what — проверяю ещё раз")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastHealAt < MIN_HEAL_INTERVAL_MS) {
            appendLog("$what — чинил недавно, жду")
            return
        }

        lastHealAt = now
        consecutiveFailures = 0
        appendLog("$what — перезапускаю соединения")
        val failure = runCatching { heal() }.exceptionOrNull()
        if (failure != null) {
            appendLog("починить не удалось: ${failure.message ?: failure.toString()}")
        }
        delay(SETTLE_AFTER_HEAL_MS)
    }

    /** Проба под таймаутом: зависшая сеть не должна подвесить сам сторож. */
    private suspend fun probe(block: () -> Boolean): Boolean =
        withTimeoutOrNull(PROBE_TIMEOUT_MS.toLong() + 2_000L) {
            runCatching { block() }.getOrDefault(false)
        } ?: false

    /** Туннель без DNS: обычный TCP до литерального адреса. */
    private fun tcpReachable(): Boolean = Socket().use { socket ->
        socket.connect(InetSocketAddress(InetAddress.getByName(PROBE_IP), PROBE_PORT), PROBE_TIMEOUT_MS)
        socket.isConnected
    }

    /** Полный путь: резолв имени + реальный запрос. */
    private fun domainReachable(): Boolean {
        InetAddress.getByName(PROBE_HOST) ?: return false
        val connection = URL(PROBE_URL).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.responseCode in 200..399
        } finally {
            connection.disconnect()
        }
    }

    private fun appendLog(message: String) {
        val stamp = SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date())
        val lines = ("$stamp — $message").lineSequence().toList() +
            Settings.watchdogLog.lineSequence().filter { it.isNotBlank() }
        Settings.watchdogLog = lines.take(MAX_LOG_LINES).joinToString("\n")
    }
}
