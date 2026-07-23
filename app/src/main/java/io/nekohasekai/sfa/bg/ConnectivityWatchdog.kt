package io.nekohasekai.sfa.bg

import android.net.NetworkCapabilities
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Починка идёт по нарастающей. Первый раз — переподключение (reload): часто виноваты залипшие
 * сокеты после смены сети, и этого достаточно. Если не помогло — уводим на автоподбор и
 * заставляем перемерить задержки, то есть уходим на другой узел, ровно как при ручной смене
 * сервера: это лечит случай, когда деградировал сам узел (по журналу с устройства — таймаут
 * TCP к нему), а reload упирался бы в ту же стену. Каждое событие — в журнал (Настройки →
 * Автопочинка).
 */
object ConnectivityWatchdog {

    private const val CHECK_INTERVAL_MS = 60_000L
    private const val FIRST_CHECK_DELAY_MS = 30_000L      // дать туннелю подняться
    private const val PROBE_TIMEOUT_MS = 8_000
    private const val FAILURES_BEFORE_HEAL = 2
    private const val MIN_HEAL_INTERVAL_MS = 3 * 60_000L  // защита от цикла перезагрузок
    private const val BACKOFF_HEAL_INTERVAL_MS = 15 * 60_000L // канал плох — чиним реже
    private const val SETTLE_AFTER_HEAL_MS = 20_000L
    private const val MAX_LOG_LINES = 30

    private const val PROBE_IP = "1.1.1.1"
    private const val PROBE_PORT = 443
    private const val PROBE_URL = "https://www.gstatic.com/generate_204"
    private const val PROBE_HOST = "www.gstatic.com"

    private const val SETTLE_AFTER_NETWORK_MS = 5_000L

    // Теги из sub.php: селектор «Howl», внутри него автоподбор «auto» (urltest).
    // Если сервер сгенерирован иначе — вызовы ниже просто ничего не сделают, это безопасно.
    private const val SELECTOR_TAG = "Howl"
    private const val AUTO_TAG = "auto"

    private var scope: CoroutineScope? = null
    private var consecutiveFailures = 0
    private var lastHealAt = 0L
    // Сколько раз чинили подряд, не добившись успеха. 0 — свежий сбой, лечим мягко
    // (просто переподключением); ≥1 — прошлый reload не помог, значит дело не в залипших
    // сокетах, а в самом узле → уводим на автоподбор, как при ручной смене сервера.
    private var healAttempts = 0
    private var heal: (suspend () -> Unit)? = null
    private var networkJob: Job? = null

    // Проверки не должны накладываться: минутный цикл и проверка по смене сети могут
    // прийтись на один момент, а каждая занимает до 20 секунд.
    private val checkMutex = Mutex()

    fun start(heal: suspend () -> Unit) {
        stop()
        if (!Settings.watchdogEnabled) return
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope
        this.heal = heal
        consecutiveFailures = 0
        healAttempts = 0
        newScope.launch { loop() }
        DefaultNetworkMonitor.onNetworkChanged = { onNetworkChanged() }
    }

    fun stop() {
        DefaultNetworkMonitor.onNetworkChanged = null
        networkJob = null
        scope?.cancel()
        scope = null
        heal = null
        consecutiveFailures = 0
        healAttempts = 0
    }

    /**
     * Смена Wi-Fi ↔ мобильной — самый частый момент, когда соединения умирают незаметно.
     * Ждём, пока сеть устаканится, и проверяем связь сразу, не дожидаясь минутного цикла.
     */
    private fun onNetworkChanged() {
        val currentScope = scope ?: return
        networkJob?.cancel()
        networkJob = currentScope.launch {
            delay(SETTLE_AFTER_NETWORK_MS)
            appendLog("сменилась сеть — проверяю связь")
            runCatching { checkOnce() }
        }
    }

    private suspend fun loop() {
        delay(FIRST_CHECK_DELAY_MS)
        val currentScope = scope ?: return
        while (currentScope.isActive) {
            runCatching { checkOnce() }
            delay(CHECK_INTERVAL_MS)
        }
    }

    private suspend fun checkOnce() = checkMutex.withLock {
        // Нет сети вообще (самолётный режим, нет сигнала) — не наша беда, чинить нечего.
        if (DefaultNetworkMonitor.defaultNetwork == null) {
            consecutiveFailures = 0
            return@withLock
        }

        val tunnelOk = probe { tcpReachable() }
        val domainOk = probe { domainReachable() }

        if (tunnelOk && domainOk) {
            if (consecutiveFailures > 0 || healAttempts > 0) {
                appendLog("связь восстановилась")
            }
            consecutiveFailures = 0
            healAttempts = 0
            return@withLock
        }

        // ★ Сначала выясняем, ЧЬЯ это беда. Перезапуск лечит только зависший туннель; если у
        // самого телефона нет рабочего интернета (плохой сигнал, каптивный Wi-Fi с логином),
        // перезапуск бесполезен и вреден — он рвёт и те соединения, что ещё проходят.
        // По журналу с устройства именно так и было: в окне сбоя к узлу проходило 25 соединений
        // из 95, а сторож дважды перезапускал ядро впустую.
        when (underlyingNetwork()) {
            UnderlyingNetwork.CAPTIVE_PORTAL -> {
                appendLog("Wi-Fi требует входа в систему — VPN не пройдёт, пока не войдёте")
                consecutiveFailures = 0
                return@withLock
            }

            UnderlyingNetwork.NO_INTERNET -> {
                appendLog("у сети телефона нет интернета — жду, перезапуск не поможет")
                consecutiveFailures = 0
                return@withLock
            }

            UnderlyingNetwork.OK -> Unit
        }

        consecutiveFailures++
        val what = when {
            !tunnelOk && !domainOk -> "туннель не отвечает"
            tunnelOk && !domainOk -> "DNS не отвечает (туннель жив)"
            else -> "домен открывается, прямой канал нет"
        }

        if (consecutiveFailures < FAILURES_BEFORE_HEAL) {
            appendLog("$what — проверяю ещё раз")
            return@withLock
        }

        // Чем больше безуспешных починок подряд, тем дольше ждём перед следующей: если две
        // попытки не помогли, дело почти наверняка в качестве канала, а не в туннеле, и частые
        // перезапуски только рвут те соединения, что ещё проходят.
        val now = System.currentTimeMillis()
        val waitBeforeHeal = if (healAttempts >= 2) BACKOFF_HEAL_INTERVAL_MS else MIN_HEAL_INTERVAL_MS
        if (now - lastHealAt < waitBeforeHeal) {
            appendLog("$what — чинил недавно, жду")
            return@withLock
        }

        lastHealAt = now
        consecutiveFailures = 0

        // Первый раз — просто переподключаемся: часто виноваты залипшие сокеты (например,
        // после смены сети), и reload их пересоздаёт. Если это уже не первая починка в этом
        // эпизоде, значит reload не помог — виноват сам узел, и надо уходить на другой.
        if (healAttempts == 0) {
            appendLog("$what — переподключаюсь")
            val failure = runCatching { heal?.invoke() }.exceptionOrNull()
            if (failure != null) {
                appendLog("переподключиться не удалось: ${failure.message ?: failure.toString()}")
            }
        } else {
            appendLog("$what — не помогло, меняю сервер")
            switchToFastestServer()
            val failure = runCatching { heal?.invoke() }.exceptionOrNull()
            if (failure != null) {
                appendLog("сменить сервер не удалось: ${failure.message ?: failure.toString()}")
            }
        }
        healAttempts++
        delay(SETTLE_AFTER_HEAL_MS)
    }

    /**
     * Уводит на автоподбор и заставляет перемерить задержки — то же, что делает ручная смена
     * сервера: если узел, на котором мы сидим, деградировал, urltest выберет живой и быстрый.
     * Помогает и когда пользователь вручную закрепился на конкретной локации, которая отвалилась.
     */
    private fun switchToFastestServer() {
        // Standalone-клиент здесь одноразовый (fire-and-forget), как во всех остальных местах
        // проекта — connect/close не нужны.
        runCatching {
            val client = Libbox.newStandaloneCommandClient()
            client.selectOutbound(SELECTOR_TAG, AUTO_TAG)
            client.urlTest(SELECTOR_TAG)
        }
    }

    private enum class UnderlyingNetwork { OK, NO_INTERNET, CAPTIVE_PORTAL }

    /**
     * Что думает о нижележащей сети сама система. Android постоянно проверяет каждую сеть на
     * реальный выход в интернет — это тот самый механизм, который рисует «!» на значке Wi-Fi
     * и предлагает войти в гостевую сеть. Переиспользуем его вердикт вместо своих догадок.
     *
     * Сеть здесь физическая (Wi-Fi/мобильная), а не наш tun: DefaultNetworkListener специально
     * запрашивает не-VPN сеть. При сомнениях считаем, что сеть в порядке, — лучше лишний раз
     * попробовать починить, чем молча ничего не делать.
     */
    private fun underlyingNetwork(): UnderlyingNetwork {
        val network = DefaultNetworkMonitor.defaultNetwork ?: return UnderlyingNetwork.OK
        val caps = runCatching {
            Application.connectivity.getNetworkCapabilities(network)
        }.getOrNull() ?: return UnderlyingNetwork.OK
        return when {
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) ->
                UnderlyingNetwork.CAPTIVE_PORTAL

            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ->
                UnderlyingNetwork.NO_INTERNET

            else -> UnderlyingNetwork.OK
        }
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
