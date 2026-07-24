package io.nekohasekai.sfa.bg

import android.net.NetworkCapabilities
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
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
 * Починка идёт по нарастающей — три ступени, каждая лечит свою причину:
 *   1) переподключение — залипшие сокеты после смены сети;
 *   2) другая СЕТЬ телефона — телефон держится за мёртвый Wi-Fi, хотя рядом живая мобильная
 *      (по журналу 23.07: `dial wlan0: no route to host` за 6 мс — сеть отвергала пакеты сразу,
 *      при этом мобильная была доступна; reload не спасал, т.к. ядру отдавалась та же сеть);
 *   3) другой СЕРВЕР — деградация узла.
 * Пока идёт сбой, проверки чаще (15 с вместо 60), чтобы пройти лестницу за полминуты, а не
 * за минуты. Каждое событие — в журнал (Настройки → Автопочинка).
 */
object ConnectivityWatchdog {

    private const val CHECK_INTERVAL_MS = 60_000L
    // Пока идёт сбой, проверяем чаще: минута простоя — это уже «интернет пропал» для человека.
    private const val CHECK_INTERVAL_FAILING_MS = 15_000L
    private const val FIRST_CHECK_DELAY_MS = 30_000L      // дать туннелю подняться
    private const val PROBE_TIMEOUT_MS = 8_000
    private const val FAILURES_BEFORE_HEAL = 2
    // Между ступенями лестницы (переподключение → другая сеть → другой сервер) ждём немного:
    // иначе ступени не успевают отработать. Полный откат к длинным паузам — только когда
    // лестница пройдена и ясно, что дело в качестве канала.
    private const val MIN_HEAL_INTERVAL_MS = 30_000L
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
            appendLog(str(R.string.watchdog_log_network_changed))
            runCatching { checkOnce() }
        }
    }

    private suspend fun loop() {
        delay(FIRST_CHECK_DELAY_MS)
        val currentScope = scope ?: return
        while (currentScope.isActive) {
            runCatching { checkOnce() }
            // Всё хорошо — проверяем раз в минуту; идёт сбой — каждые 15 секунд, чтобы пройти
            // лестницу починки быстро, а не растягивать простой на минуты.
            val failing = consecutiveFailures > 0 || healAttempts > 0
            delay(if (failing) CHECK_INTERVAL_FAILING_MS else CHECK_INTERVAL_MS)
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
                appendLog(str(R.string.watchdog_log_recovered))
                // Возвращаемся к обычному выбору сети: если во время сбоя ушли на мобильную,
                // а Wi-Fi ожил — надо вернуться на него, иначе будем зря жечь мобильный трафик.
                if (healAttempts > 0) DefaultNetworkMonitor.reevaluate()
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
                appendLog(str(R.string.watchdog_log_captive))
                consecutiveFailures = 0
                return@withLock
            }

            UnderlyingNetwork.NO_INTERNET -> {
                appendLog(str(R.string.watchdog_log_no_internet))
                consecutiveFailures = 0
                return@withLock
            }

            UnderlyingNetwork.OK -> Unit
        }

        consecutiveFailures++
        val what = when {
            !tunnelOk && !domainOk -> str(R.string.watchdog_reason_tunnel)
            tunnelOk && !domainOk -> str(R.string.watchdog_reason_dns)
            else -> str(R.string.watchdog_reason_direct)
        }

        if (consecutiveFailures < FAILURES_BEFORE_HEAL) {
            appendLog(str(R.string.watchdog_log_recheck, what))
            return@withLock
        }

        // Чем больше безуспешных починок подряд, тем дольше ждём перед следующей: если две
        // попытки не помогли, дело почти наверняка в качестве канала, а не в туннеле, и частые
        // перезапуски только рвут те соединения, что ещё проходят.
        // Лестницу (0→1→2) проходим быстро; дальше, если ничего не помогло, — редкие попытки.
        val now = System.currentTimeMillis()
        val waitBeforeHeal = if (healAttempts > 2) BACKOFF_HEAL_INTERVAL_MS else MIN_HEAL_INTERVAL_MS
        if (now - lastHealAt < waitBeforeHeal) {
            appendLog(str(R.string.watchdog_log_wait, what))
            return@withLock
        }

        lastHealAt = now
        consecutiveFailures = 0

        // Лестница починки: сначала дёшево, потом радикальнее.
        //   1) переподключение — лечит залипшие сокеты после смены сети;
        //   2) другая СЕТЬ телефона — лечит «держимся за мёртвый Wi-Fi, а рядом живая мобильная»
        //      (ровно этот случай reload не чинил: ядру отдавалась та же нерабочая сеть);
        //   3) другой СЕРВЕР — лечит деградацию узла.
        when (healAttempts) {
            0 -> {
                appendLog(str(R.string.watchdog_log_reconnect, what))
                runHeal(str(R.string.watchdog_act_reconnect))
            }

            1 -> {
                DefaultNetworkMonitor.reevaluate(preferAlternative = true)
                val via = DefaultNetworkMonitor.reportedInterface
                appendLog(
                    if (via != null) str(R.string.watchdog_log_switch_network_via, what, via)
                    else str(R.string.watchdog_log_switch_network, what),
                )
                runHeal(str(R.string.watchdog_act_switch_network))
            }

            else -> {
                appendLog(str(R.string.watchdog_log_switch_server, what))
                switchToFastestServer()
                runHeal(str(R.string.watchdog_act_switch_server))
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
    /** Строки журнала берём из ресурсов: журнал видит пользователь, а языков у приложения пять. */
    private fun str(id: Int, vararg args: Any): String =
        runCatching { Application.application.getString(id, *args) }.getOrDefault("")

    private suspend fun runHeal(what: String) {
        val failure = runCatching { heal?.invoke() }.exceptionOrNull()
        if (failure != null) {
            appendLog(str(R.string.watchdog_log_failed, what, failure.message ?: failure.toString()))
        }
    }

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
