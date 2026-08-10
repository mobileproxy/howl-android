package io.nekohasekai.sfa.bg

import android.net.NetworkCapabilities
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.subscription.ProfileTags
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.AppEventLog
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
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
import java.util.Collections
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Сторож соединения: ловит состояние «подключено, но трафик не идёт».
 *
 * Зачем нужен. Пинг в списке серверов НЕ доказывает, что туннель жив: во-первых, это последнее
 * удачное измерение, а не текущее; во-вторых, проба urltest идёт мимо локального DNS — имя
 * разрешает сам прокси-сервер. Поэтому зависшее DNS-соединение внутри туннеля (или мёртвые
 * сокеты после смены Wi-Fi↔LTE) выглядят как «всё зелёное», хотя ни один сайт не открывается.
 * Ядро такое не замечает и само не чинит.
 *
 * Что делает. Прогоняет ДВЕ разные пробы — так видно, что именно сломалось:
 *   • TCP на 1.1.1.1:443 — без DNS, проверяет сам туннель;
 *   • запрос к домену — проверяет DNS и путь целиком.
 * Трафик приложения идёт через тот же tun, что и у браузера, — это честная сквозная проверка.
 *
 * Когда проверяет. Не по расписанию, а по СОБЫТИЯМ — чтобы реагировать за секунды, а не «когда-
 * нибудь на минутном тике»:
 *   • включился экран или открыли приложение — человек взялся за телефон, проверяем ЗАРАНЕЕ;
 *   • сменилась сеть (Wi-Fi ↔ мобильная) — самый частый момент тихой смерти сокетов;
 *   • залипание трафика — ядро сообщает, что мы шлём, а в ответ тишина (ловится за ~9 секунд).
 * Фоновый цикл раз в 1.5 минуты остаётся страховкой на случай, если ни одно событие не пришло.
 * Все триггеры лишь ЗАПУСКАЮТ пробу; чинит только реальный провал пробы, поэтому ложная тревога
 * безвредна.
 *
 * Починка идёт по нарастающей — три ступени, каждая лечит свою причину:
 *   1) переподключение — залипшие сокеты после смены сети;
 *   2) другая СЕТЬ телефона — телефон держится за мёртвый Wi-Fi, хотя рядом живая мобильная
 *      (по журналу 23.07: `dial wlan0: no route to host` за 6 мс — сеть отвергала пакеты сразу,
 *      при этом мобильная была доступна; reload не спасал, т.к. ядру отдавалась та же сеть);
 *   3) другой СЕРВЕР — деградация узла.
 * Пока идёт сбой, проверки чаще (15 с вместо 60), чтобы пройти лестницу за полминуты, а не
 * за минуты. Каждое событие — в журнал (Настройки → Автопочинка).
 *
 * ★ Правка 24.07.2026. Вердикт «у телефона нет интернета» раньше приводил к ВЫХОДУ из починки —
 * и лестница никогда не доходила до ступени 2 ровно в том случае, ради которого её и писали.
 * В журнале ядра это видно как сотни `dial wlan0 (27): no route to host` подряд на протяжении
 * сорока минут; спасало только ручное переподключение. Теперь мёртвая сеть, наоборот, сразу
 * ведёт на ступень 2, минуя бесполезное переподключение. Вдобавок уходить стало КУДА: пока
 * телефон держится за Wi-Fi, Android гасит модем, поэтому мобильную сеть мы теперь поднимаем
 * заявкой сами, а сеть, через которую трафик не пошёл, попадает в штрафной ящик на три минуты,
 * чтобы следующий же системный колбэк не вернул нас на неё обратно.
 */
object ConnectivityWatchdog {

    // Фоновый цикл — это СТРАХОВКА, а не основной способ заметить сбой. Основное — события ниже
    // (экран, передний план, залипание трафика), которые ловят проблему за секунды. Поэтому в
    // норме цикл редкий: будить телефон чаще незачем, когда есть мгновенные сигналы.
    private const val CHECK_INTERVAL_MS = 90_000L
    // Пока идёт сбой, проверяем чаще: минута простоя — это уже «интернет пропал» для человека.
    private const val CHECK_INTERVAL_FAILING_MS = 15_000L
    private const val FIRST_CHECK_DELAY_MS = 30_000L      // дать туннелю подняться

    // Внеплановые проверки (экран, передний план, залипание) не должны идти лавиной: между ними
    // выдерживаем паузу. Проба и так занимает до ~16с, чаще смысла нет, а поток событий (каждое
    // уведомление зажигает экран) иначе устроил бы шквал проб.
    private const val MIN_TRIGGER_INTERVAL_MS = 8_000L
    // Залипание трафика: приложение ЗАМЕТНО шлёт, а в ответ полная тишина дольше этого срока —
    // верный признак вставшего туннеля. Отдельные простои (ничего не качаем) сюда не попадают:
    // условие требует именно активной отправки без единого ответного байта.
    private const val TRAFFIC_STALL_MS = 9_000L
    private const val TRAFFIC_STALL_UPLINK_BPS = 4_096L  // «шлём», а не фоновый keep-alive
    private const val PROBE_TIMEOUT_MS = 8_000
    private const val FAILURES_BEFORE_HEAL = 2
    // Между ступенями лестницы (переподключение → другая сеть → другой сервер) ждём немного:
    // иначе ступени не успевают отработать. Полный откат к длинным паузам — только когда
    // лестница пройдена и ясно, что дело в качестве канала.
    private const val MIN_HEAL_INTERVAL_MS = 30_000L

    // ★ Было 15 минут — и это оказалось второй по величине дырой. По журналу 29.07: 06:49:30 →
    // 07:03:26 сторож 53 раза подряд написал «чинил недавно, жду» и не сделал НИЧЕГО, пока туннель
    // был мёртв. Пауза уместна только когда канал в принципе плох и частые перезапуски лишь рвут
    // то, что ещё проходит; две минуты для этого достаточно. При мёртвом туннеле пауза не
    // применяется вовсе (см. waitBeforeHeal).
    private const val BACKOFF_HEAL_INTERVAL_MS = 2 * 60_000L

    // Проверки приходят из трёх источников (будильник, фоновый цикл, события). Совпали по времени —
    // вторую пропускаем: пробы занимают до ~16 с, дублировать их незачем.
    private const val MIN_CHECK_SPACING_MS = 5_000L

    // Как часто подтверждать в журнале, что сторож жив и всё в порядке. Реже — снова появится
    // двусмысленная тишина; чаще — журнал утонет в однотипных строках.
    private const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L
    private const val SETTLE_AFTER_HEAL_MS = 20_000L

    // Цели для проверки САМОЙ сети телефона (мимо туннеля). 1.1.1.1 у части РФ-провайдеров
    // недоступен, поэтому нужен запасной путь — Яндекс-DNS в России доступен практически всегда.
    private val UNDERLYING_PROBE_IPS = listOf("1.1.1.1", "77.88.8.8", "8.8.8.8")

    // Каждая цель со своим коротким таймаутом: три попытки по 8 с подвесили бы проверку на 24 с.
    private const val UNDERLYING_PROBE_TIMEOUT_MS = 3_000

    private const val PROBE_IP = "1.1.1.1"
    private const val PROBE_PORT = 443
    private const val PROBE_URL = "https://www.gstatic.com/generate_204"
    private const val PROBE_HOST = "www.gstatic.com"

    private const val SETTLE_AFTER_NETWORK_MS = 5_000L

    // Узел считаем пригодным для принудительного увода, только если автоподбор ИЗМЕРИЛ его
    // задержку и она разумная. 0 = не измерен/недоступен, слишком большая = мёртвый.
    private const val MAX_NODE_DELAY_MS = 3_000

    // Теги групп НЕ зашиты: «Howl»/«auto» — это имена из нашего sub.php, а у профиля от
    // стороннего сервиса они другие. С зашитыми именами ступень «сменить сервер» на чужом
    // профиле молча ничего не делала: ошибки нет, починки тоже. Читаем их из самого конфига
    // при старте службы — см. ProfileTags.

    private var scope: CoroutineScope? = null
    private var consecutiveFailures = 0
    private var lastHealAt = 0L

    // Когда последний раз реально прогнали пробы — чтобы будильник и фоновый цикл, сойдясь в одну
    // секунду, не делали двойную работу.
    @Volatile
    private var lastCheckAt = 0L

    // Когда последний раз писали «всё в порядке» — см. HEARTBEAT_INTERVAL_MS.
    @Volatile
    private var lastHeartbeatAt = 0L
    // Сколько раз чинили подряд, не добившись успеха. 0 — свежий сбой, лечим мягко
    // (просто переподключением); ≥1 — прошлый reload не помог, значит дело не в залипших
    // сокетах, а в самом узле → уводим на автоподбор, как при ручной смене сервера.
    private var healAttempts = 0
    private var heal: (suspend () -> Unit)? = null
    private var networkJob: Job? = null

    // Внеплановые проверки по событиям: время последней (для троттлинга) и подписка на статус
    // ядра, по которой ловим залипание трафика. Volatile — пишутся из потока Command-клиента и
    // сбрасываются из start(): нужна видимость между потоками (гонка сама по себе безвредна —
    // худшее, что бывает, это лишняя проба).
    @Volatile
    private var lastTriggerAt = 0L
    private var statusClient: CommandClient? = null

    @Volatile
    private var trafficStalledSince = 0L

    // Карусель узлов. Автоподбор внутри ядра выбирает узел по короткой пробе и может залипнуть
    // на узле, который на пробу отвечает, но реальный трафик через него у ЭТОГО клиента не идёт
    // (плохой маршрут провайдера — сам узел при этом здоров). Тогда сторож уводит на КОНКРЕТНЫЙ
    // другой узел, а залипший кладёт в штрафной ящик. Подписка на группы даёт список узлов с
    // измеренными задержками и текущий выбор.
    private var groupsClient: CommandClient? = null

    // Узлы группы автоподбора: тег → задержка (мс, 0 = не измерен/недоступен). Обновляется ядром.
    @Volatile
    private var autoNodes: List<Pair<String, Int>> = emptyList()

    // Что автоподбор выбрал прямо сейчас.
    @Volatile
    private var autoSelected: String? = null

    // На что указывает селектор — узел, через который ФАКТИЧЕСКИ идёт трафик. Именно с него
    // уводим при залипании (см. комментарий в GroupsHandler о зацикливании карусели).
    @Volatile
    private var selectorSelected: String? = null

    // Все узлы селектора (VLESS, Hysteria2, AmneziaWG) с измеренными задержками — запасной пул
    // карусели, когда узлы автоподбора закончились.
    @Volatile
    private var selectorNodes: List<Pair<String, Int>> = emptyList()

    // Узлы, на которых уже залипли в текущей сети. Сбрасывается при смене сети (маршруты меняются)
    // и когда перепробованы все (тогда возвращаемся к автоподбору с чистого листа).
    // synchronizedSet: пишется из проверок, чистится из onNetworkChanged/start/stop — разные
    // потоки, нужна согласованная видимость. (newKeySet не годится — требует API 24, minSdk 23.)
    private val stuckNodes: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    // Проверки не должны накладываться: фоновый цикл, смена сети и событийные триггеры могут
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
        // Первые FIRST_CHECK_DELAY_MS не триггерим: туннель ещё поднимается, ложный «сбой» тут
        // привёл бы к починке поднимающегося туннеля. Троттл, выставленный «в будущее», это гасит.
        lastTriggerAt = System.currentTimeMillis() + FIRST_CHECK_DELAY_MS
        trafficStalledSince = 0L
        stuckNodes.clear()
        autoNodes = emptyList()
        autoSelected = null
        selectorSelected = null
        lastCheckAt = 0L
        lastHeartbeatAt = 0L
        // ★ Явно фиксируем перезапуск сторожа и сброс счётчиков. Без этой строки в журнале после
        // «[старт]» просто пропадали сообщения о починке — счётчики обнулены, прошлый сбой уже не
        // «продолжается», и «связь восстановилась» больше не печаталось. Со стороны выглядело как
        // многочасовой обрыв, которого не было.
        appendLog("сторож запущен, счётчики сброшены")
        newScope.launch { loop() }
        newScope.launch { watchUserPresence() }
        DefaultNetworkMonitor.onNetworkChanged = { onNetworkChanged() }
        startTrafficMonitor(newScope)
        startGroupsMonitor(newScope)
        // ★ Будильник — то, что заставляет сторожа работать при спящем экране. Фоновый цикл выше
        // остаётся, но он тикает только пока устройство не спит; на сон полагаемся на будильник.
        WatchdogAlarm.start { onAlarmTick() }
        WatchdogAlarm.schedule(FIRST_CHECK_DELAY_MS)
    }

    /**
     * Сработал будильник (устройство разбужено системой, CPU удержан). Проверяем связь и тут же
     * ставим следующий будильник: интервал зависит от того, идёт ли сейчас сбой.
     */
    private fun onAlarmTick() {
        val currentScope = scope ?: return
        currentScope.launch {
            runCatching { checkOnce("будильник") }
            scheduleNextAlarm()
        }
    }

    private fun scheduleNextAlarm() {
        val failing = consecutiveFailures > 0 || healAttempts > 0
        WatchdogAlarm.schedule(if (failing) CHECK_INTERVAL_FAILING_MS else CHECK_INTERVAL_MS)
    }

    fun stop() {
        WatchdogAlarm.stop()
        DefaultNetworkMonitor.onNetworkChanged = null
        // Модем поднимали только ради починки — держать его дальше незачем.
        DefaultNetworkMonitor.releaseCellularBackup()
        DefaultNetworkMonitor.clearPenalties()
        statusClient?.let { runCatching { it.disconnect() } }
        statusClient = null
        groupsClient?.let { runCatching { it.disconnect() } }
        groupsClient = null
        stuckNodes.clear()
        networkJob = null
        scope?.cancel()
        scope = null
        heal = null
        consecutiveFailures = 0
        healAttempts = 0
    }

    /**
     * Человек взялся за телефон — включил экран или открыл приложение. Скорее всего он вот-вот
     * начнёт пользоваться сетью, поэтому проверяем связь ЗАРАНЕЕ: если что-то отвалилось, пока
     * телефон лежал, к моменту, когда откроют браузер, оно уже будет чиниться, а не «через минуту».
     */
    private suspend fun watchUserPresence() {
        merge(
            AppLifecycleObserver.isScreenOn.filter { it },
            AppLifecycleObserver.isForeground.filter { it },
        ).collect { triggerImmediateCheck("экран/приложение") }
    }

    /**
     * Подписка на статус ядра (те же цифры uplink/downlink, что в уведомлении). Ловит залипание
     * «шлём, а в ответ тишина» за секунды и в фоне — это и есть быстрая реакция без частого
     * опроса. Клиент локальный (в пределах процесса), сеть не трогает.
     */
    private fun startTrafficMonitor(currentScope: CoroutineScope) {
        val client = CommandClient(currentScope, CommandClient.ConnectionType.Status, TrafficHandler, localOnly = true)
        statusClient = client
        runCatching { client.connect() }
    }

    private object TrafficHandler : CommandClient.Handler {
        override fun updateStatus(status: StatusMessage) {
            // Шлём заметно, но в ответ НИ БАЙТА — засекаем, с какого момента длится тишина.
            val sending = status.uplink >= TRAFFIC_STALL_UPLINK_BPS
            val receiving = status.downlink > 0
            if (sending && !receiving) {
                val now = System.currentTimeMillis()
                if (trafficStalledSince == 0L) {
                    trafficStalledSince = now
                } else if (now - trafficStalledSince >= TRAFFIC_STALL_MS) {
                    triggerImmediateCheck("залипание трафика")
                }
            } else {
                // Пришёл хоть один ответный байт (или перестали слать) — тишины нет.
                trafficStalledSince = 0L
            }
        }
    }

    /**
     * Подписка на группы ядра: список узлов автоподбора с измеренными задержками и текущий выбор.
     * Нужна карусели узлов — чтобы при залипании увести на конкретный рабочий узел, а не
     * переспрашивать автоподбор (он вернёт тот же залипший).
     */
    private fun startGroupsMonitor(currentScope: CoroutineScope) {
        val client = CommandClient(currentScope, CommandClient.ConnectionType.Groups, GroupsHandler, localOnly = true)
        groupsClient = client
        runCatching { client.connect() }
    }

    private object GroupsHandler : CommandClient.Handler {
        override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
            // ★ На что реально указывает СЕЛЕКТОР — это и есть узел, через который идёт трафик.
            // Раньше карусель считала текущим узлом выбор ГРУППЫ АВТОПОДБОРА, а это разные вещи:
            // после первого же принудительного увода селектор смотрит на конкретный узел, а
            // автоподбор продолжает показывать свой (самый быстрый по пробе). В журнале 30.07 это
            // дало 25 одинаковых записей «увёл на NL (был DE)»: мы каждый раз «уводили» с DE, уже
            // находясь на NL, — то есть НЕ УХОДИЛИ НИКУДА, и мёртвое соединение так и оставалось
            // мёртвым до ручного переподключения.
            ProfileTags.selector?.let { selectorTag ->
                val selector = newGroups.firstOrNull { it.tag == selectorTag }
                selectorSelected = selector?.selected?.takeIf { it.isNotBlank() }

                // ★ Узлы ВСЕХ протоколов. В нашей подписке группа автоподбора состоит только из
                // AmneziaWG (это осознанный приоритет против DPI), а VLESS и Hysteria2 лежат
                // отдельно в селекторе — то есть доступны лишь вручную. Из-за этого карусель не
                // могла уйти с ломающегося WG в принципе: в журнале 02–03.08 все 12 переключений
                // были между тремя WG-узлами, и трижды сторож увёл ЧЕЛОВЕКА С РАБОТАЮЩЕГО VLESS
                // на WG. Собираем полный список, чтобы было куда отступать.
                val all = mutableListOf<Pair<String, Int>>()
                if (selector != null) {
                    val selectorItems = selector.items
                    while (selectorItems.hasNext()) {
                        val item = selectorItems.next()
                        // Сама группа автоподбора — не узел, на неё уводить бессмысленно.
                        if (item.tag != ProfileTags.auto) all.add(item.tag to item.urlTestDelay)
                    }
                }
                selectorNodes = all
            }

            // Группа автоподбора — по тегу из конфига (у чужого профиля он другой).
            val autoTag = ProfileTags.auto ?: return
            val auto = newGroups.firstOrNull { it.tag == autoTag } ?: return
            autoSelected = auto.selected.takeIf { it.isNotBlank() }
            val nodes = mutableListOf<Pair<String, Int>>()
            val items = auto.items
            while (items.hasNext()) {
                val item = items.next()
                nodes.add(item.tag to item.urlTestDelay)
            }
            autoNodes = nodes
        }
    }

    /**
     * Внеплановая проверка — не дожидаясь очередного тика цикла. Зовётся по событиям, каждое из
     * которых означает «сейчас человек, скорее всего, упрётся в проблему». Троттлинг обязателен:
     * события идут пачками (уведомления зажигают экран), а проба длится секунды.
     *
     * Ключевое: триггер лишь УСКОРЯЕТ диагностику, но не чинит вслепую — сама починка идёт только
     * если проба реально провалилась. Поэтому ложная тревога безвредна, и сигналы можно держать
     * чувствительными.
     */
    private fun triggerImmediateCheck(trigger: String = "событие") {
        val currentScope = scope ?: return
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < MIN_TRIGGER_INTERVAL_MS) return
        lastTriggerAt = now
        currentScope.launch { runCatching { checkOnce(trigger) } }
    }

    /**
     * Смена Wi-Fi ↔ мобильной — самый частый момент, когда соединения умирают незаметно.
     * Ждём, пока сеть устаканится, и проверяем связь сразу, не дожидаясь минутного цикла.
     */
    private fun onNetworkChanged() {
        val currentScope = scope ?: return
        // Сеть сменилась — маршруты к узлам стали другими, прошлые «плохие узлы» больше не
        // приговор. Чистим штрафной ящик, чтобы карусель могла снова пробовать любой узел.
        stuckNodes.clear()
        networkJob?.cancel()
        networkJob = currentScope.launch {
            delay(SETTLE_AFTER_NETWORK_MS)
            appendLog(str(R.string.watchdog_log_network_changed))
            runCatching { checkOnce("смена сети") }
        }
    }

    private suspend fun loop() {
        delay(FIRST_CHECK_DELAY_MS)
        val currentScope = scope ?: return
        while (currentScope.isActive) {
            runCatching { checkOnce() }
            // Держим будильник в согласии с циклом: если устройство сейчас заснёт, следующая
            // проверка всё равно состоится — уже по будильнику.
            scheduleNextAlarm()
            // Всё хорошо — проверяем раз в минуту; идёт сбой — каждые 15 секунд, чтобы пройти
            // лестницу починки быстро, а не растягивать простой на минуты.
            val failing = consecutiveFailures > 0 || healAttempts > 0
            delay(if (failing) CHECK_INTERVAL_FAILING_MS else CHECK_INTERVAL_MS)
        }
    }

    /**
     * Короткая сводка обстановки для журнала: через какой узел идём и по какой сети. Раньше это
     * приходилось восстанавливать по ошибкам ядра, а при разборе журнала именно этих двух фактов
     * не хватало, чтобы понять «узел плохой» или «сеть плохая».
     */
    private fun context(trigger: String): String {
        val node = selectorSelected?.takeIf { it != ProfileTags.auto } ?: autoSelected
        val net = DefaultNetworkMonitor.reportedInterface
        return "узел «${node ?: "?"}» · сеть ${net ?: "?"} · проверка: $trigger"
    }

    private suspend fun checkOnce(trigger: String = "цикл") = checkMutex.withLock {
        // Только что проверяли (сошлись будильник и цикл) — второй прогон ничего не добавит.
        val startedAt = System.currentTimeMillis()
        if (startedAt - lastCheckAt < MIN_CHECK_SPACING_MS) return@withLock
        lastCheckAt = startedAt

        // Нет сети вообще (самолётный режим, нет сигнала) — не наша беда, чинить нечего.
        if (DefaultNetworkMonitor.defaultNetwork == null) {
            consecutiveFailures = 0
            return@withLock
        }

        val tunnelOk = probe { tcpReachable() }
        val domainOk = probe { domainReachable() }

        // ★ Успех определяет ТОЛЬКО domainOk. Это полный путь: резолв имени плюс живой
        // HTTPS-запрос НАСКВОЗЬ через туннель. Если он прошёл — туннель работает по
        // определению, спорить не о чем. Раньше здесь стояло `tunnelOk && domainOk`, и
        // сбой объявлялся даже когда запрос доходил: достаточно было споткнуться более
        // грубой пробе туннеля. В журнале 07–09.08 это два десятка ложных тревог, каждая
        // из которых запускала лишний цикл лечения — переподключения на ровном месте.
        // tunnelOk ниже остаётся, но уже только чтобы назвать ПРИЧИНУ настоящего сбоя.
        if (domainOk) {
            // ★ «Пульс»: раз в HEARTBEAT_INTERVAL_MS подтверждаем, что сторож жив и всё в порядке.
            // Без него УСПЕШНЫЕ проверки в журнал не попадали, и тишина читалась двояко: то ли
            // связь была в порядке, то ли сторож вообще не работал (телефон спал). При разборе
            // журнала 31.07–02.08 это дало фантомные «обрывы» на 194 и 491 минуту, которых на
            // деле не было. Раз в 15 минут — 4 строки в час, журнал не раздувают.
            val now = System.currentTimeMillis()
            if (now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS) {
                lastHeartbeatAt = now
                appendLog("всё в порядке · ${context(trigger)}")
            }
            if (consecutiveFailures > 0 || healAttempts > 0) {
                appendLog(str(R.string.watchdog_log_recovered))
                // Связь есть — прошлые вердикты о «плохих» сетях больше не актуальны, а
                // поднятый ради починки модем пора отпустить: он ест батарею и мобильный трафик.
                DefaultNetworkMonitor.clearPenalties()
                DefaultNetworkMonitor.releaseCellularBackup()
                // Возвращаемся к обычному выбору сети: если во время сбоя ушли на мобильную,
                // а Wi-Fi ожил — надо вернуться на него, иначе будем зря жечь мобильный трафик.
                if (healAttempts > 0) DefaultNetworkMonitor.reevaluate()
            }
            consecutiveFailures = 0
            healAttempts = 0
            return@withLock
        }

        consecutiveFailures++
        // Обстановка в начале каждого сбоя — отдельной строкой, чтобы не переписывать
        // локализованные сообщения ниже: сразу видно узел, сеть и чем вызвана проверка.
        if (consecutiveFailures == 1) {
            appendLog("сбой · ${context(trigger)}")
        }
        // Сюда попадаем только при domainOk == false, так что вариантов ровно два:
        // не отвечает вообще ничего — или туннель жив, а имя не резолвится.
        val what =
            if (tunnelOk) str(R.string.watchdog_reason_dns)
            else str(R.string.watchdog_reason_tunnel)

        // ★ Выясняем, ЧЬЯ это беда. Перезапуск лечит только зависший туннель; если у самого
        // телефона нет рабочего интернета, перезапуск бесполезен и вреден — он рвёт и те
        // соединения, что ещё проходят.
        val state = underlyingNetwork()

        // Каптивный Wi-Fi ждёт, пока человек войдёт на страницу гостевой сети. Тут мы бессильны.
        if (state == UnderlyingNetwork.CAPTIVE_PORTAL) {
            appendLog(str(R.string.watchdog_log_captive))
            return@withLock
        }

        // ★★ Сеть телефона мертва. РАНЬШЕ ЗДЕСЬ БЫЛ ВЫХОД — и это была главная дыра: лестница
        // починки никогда не доходила до смены сети ровно в том случае, ради которого её и
        // делали. Журнал ядра 24.07: сотни `dial wlan0 (27): no route to host` подряд с 17:20
        // до 18:03, смены сети так и не случилось, помогло только ручное переподключение.
        // ★ Вердикт системы обновляется редко и врёт. Журнал 29.07, окно 06:50–07:04: Wi-Fi
        // числился валидным, а прямые соединения через wlan0 отваливались по таймауту — сторож
        // считал сеть телефона исправной и потому уходил в долгую паузу вместо смены сети.
        // Поэтому при мёртвом туннеле дополнительно проверяем САМУ сеть телефона, минуя туннель.
        val networkDead = state == UnderlyingNetwork.NO_INTERNET ||
            (!tunnelOk && !probe { underlyingReachable() })
        if (networkDead) {
            // Пока телефон держится за Wi-Fi, Android гасит модем — и уходить физически некуда.
            // Просим систему поднять мобильную заранее: к следующей проверке она уже поднимется.
            DefaultNetworkMonitor.requestCellularBackup()
            if (!DefaultNetworkMonitor.hasAlternativeNetwork()) {
                // Альтернативы нет — это честное «интернета нет», а не наша беда. Счётчик НЕ
                // сбрасываем: проверки останутся частыми и подхватят сеть, как только она будет.
                appendLog(str(R.string.watchdog_log_no_internet))
                return@withLock
            }
        }

        // Мёртвая сеть — приговор окончательный, второе мнение не нужно: чиним сразу.
        if (consecutiveFailures < FAILURES_BEFORE_HEAL && !networkDead) {
            appendLog(str(R.string.watchdog_log_recheck, what))
            return@withLock
        }

        // Чем больше безуспешных починок подряд, тем дольше ждём перед следующей: если две
        // попытки не помогли, дело почти наверняка в качестве канала, а не в туннеле, и частые
        // перезапуски только рвут те соединения, что ещё проходят.
        // Лестницу (0→1→2) проходим быстро; дальше, если ничего не помогло, — редкие попытки.
        val now = System.currentTimeMillis()
        val tunnelDead = !tunnelOk
        // Пауза уместна ТОЛЬКО когда туннель жив, а сыплется что-то одно (например DNS) — там
        // частые перезапуски рвут работающие соединения. Мёртвый туннель и мёртвая сеть под это
        // не подпадают: связи нет вообще, ждать нечего — чиним на каждой проверке.
        val waitBeforeHeal =
            if (healAttempts > 2 && !networkDead && !tunnelDead) {
                BACKOFF_HEAL_INTERVAL_MS
            } else {
                MIN_HEAL_INTERVAL_MS
            }
        if (now - lastHealAt < waitBeforeHeal) {
            appendLog(str(R.string.watchdog_log_wait, what))
            return@withLock
        }

        lastHealAt = now
        consecutiveFailures = 0

        // Лестница починки: сначала дёшево, потом радикальнее.
        //   1) переподключение — лечит залипшие сокеты после смены сети;
        //   2) другая СЕТЬ телефона — лечит «держимся за мёртвый Wi-Fi, а рядом живая мобильная»;
        //   3) другой СЕРВЕР — лечит недоступность узла (DPI режет порт 443 к его IP и т.п.).
        // Ступень выбираем по ПРИЧИНЕ, а не только по счётчику.
        //
        // ★ Туннель мёртв (TCP-проба к 1.1.1.1 не прошла), а сеть телефона рабочая — значит
        // виноват УЗЕЛ, а не сеть и не залипшие сокеты. Смену сети тут пропускаем (сеть-то
        // живая) и после одного переподключения сразу идём на смену сервера. Иначе, как было в
        // журнале 26.07: DPI режет 443 к части узлов, а сторож тратил цикл на бесполезную смену
        // сети, растягивая простой.
        when {
            networkDead -> {
                switchPhoneNetwork(what)
            }

            // ★ Туннель мёртв, сеть телефона жива → виноват УЗЕЛ. Начинаем с самого дешёвого:
            // перевыбрать узел внутри работающего ядра. Это доли секунды и НЕ рвёт ядро, тогда
            // как перезагрузка стоит ~35 секунд простоя (в журнале — событие «старт» после каждой).
            // Делаем так, только если знаем узлы: иначе шаг был бы пустым.
            tunnelDead && healAttempts == 0 && autoNodes.isNotEmpty() -> {
                appendLog(str(R.string.watchdog_log_switch_server, what))
                switchToFastestServer()
            }

            tunnelDead && healAttempts >= 2 -> {
                appendLog(str(R.string.watchdog_log_switch_server, what))
                switchToFastestServer()
                runHeal(str(R.string.watchdog_act_switch_server))
            }

            tunnelDead -> {
                appendLog(str(R.string.watchdog_log_reconnect, what))
                runHeal(str(R.string.watchdog_act_reconnect))
            }

            healAttempts == 0 -> {
                appendLog(str(R.string.watchdog_log_reconnect, what))
                runHeal(str(R.string.watchdog_act_reconnect))
            }

            healAttempts == 1 -> {
                switchPhoneNetwork(what)
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

    /** Ступень «уйти на другую сеть телефона» — вынесена, т.к. зовётся из двух веток лестницы. */
    private suspend fun switchPhoneNetwork(what: String) {
        // Порядок важен: сначала штраф текущей сети, иначе перевыбор вернёт её же.
        DefaultNetworkMonitor.markCurrentNetworkBad()
        DefaultNetworkMonitor.requestCellularBackup()
        DefaultNetworkMonitor.reevaluate(preferAlternative = true)
        val via = DefaultNetworkMonitor.reportedInterface
        appendLog(
            if (via != null) str(R.string.watchdog_log_switch_network_via, what, via)
            else str(R.string.watchdog_log_switch_network, what),
        )
        runHeal(str(R.string.watchdog_act_switch_network))
    }

    private suspend fun runHeal(what: String) {
        val failure = runCatching { heal?.invoke() }.exceptionOrNull()
        if (failure != null) {
            appendLog(str(R.string.watchdog_log_failed, what, failure.message ?: failure.toString()))
        }
    }

    /**
     * Карусель узлов. Автоподбор мог залипнуть на узле, который отвечает на короткую пробу, но
     * реальный трафик через него у этого клиента не идёт (плохой маршрут). Поэтому:
     *   1) залипший узел — в штрафной ящик;
     *   2) уводим на КОНКРЕТНЫЙ следующий узел с валидной задержкой, минуя штрафные, — а не
     *      переспрашиваем автоподбор (он вернул бы тот же залипший, ведь на пробу узел отвечает);
     *   3) если рабочих узлов не осталось (все перепробованы) — чистим ящик и отдаём выбор
     *      автоподбору заново: вдруг маршрут восстановился.
     * Штрафы сбрасываются при смене сети — там маршруты другие, старые вердикты не актуальны.
     */
    private fun switchToFastestServer() {
        val selector = ProfileTags.selector ?: return
        // Standalone-клиент здесь одноразовый (fire-and-forget), как во всех остальных местах
        // проекта — connect/close не нужны.
        runCatching {
            val client = Libbox.newStandaloneCommandClient()
            // Текущий узел — тот, на который смотрит СЕЛЕКТОР (через него и идёт трафик). Если он
            // указывает на саму группу автоподбора, значит фактический узел выбирает она.
            val current = selectorSelected
                ?.takeIf { it != ProfileTags.auto }
                ?: autoSelected
            if (current != null) stuckNodes.add(current)

            // Протокол узла различаем по первому символу тега — у нас это значок (🐺 AmneziaWG,
            // 🛡 VLESS, ⚡ Hysteria2). У чужого профиля значков нет, тогда признак просто не
            // сработает и выбор пойдёт как обычно, по задержке.
            val currentKind = current?.firstOrNull()

            /**
             * Кандидат: узел с ИЗМЕРЕННОЙ разумной задержкой, не в штрафном ящике. При равных
             * условиях предпочитаем ДРУГОЙ протокол: если текущий не работает, чаще всего дело в
             * протоколе целиком (UDP режут — падает весь AmneziaWG; TCP 443 режут — падает весь
             * VLESS), и перебор соседних узлов того же типа только тратит время. Журнал 02–03.08:
             * VLESS отваливался по `i/o timeout`, AmneziaWG — по `network is unreachable`,
             * то есть у каждого свои периоды, и смена типа реально помогает.
             */
            // ★ Единый пул: узлы автоподбора И остальные узлы селектора. Раньше карусель знала
            // только про автоподбор, а он у нас состоит ИСКЛЮЧИТЕЛЬНО из AmneziaWG — VLESS и
            // Hysteria2 лежат в селекторе и были доступны лишь вручную. Поэтому при поломке WG
            // сторож бесконечно крутил три мёртвых узла и трижды за сутки стаскивал человека с
            // РАБОТАЮЩЕГО VLESS обратно на WG (журнал 02–03.08, все 12 переключений — на 🐺).
            val pool = (autoNodes + selectorNodes).distinctBy { it.first }
            val fresh = pool.filter { it.first !in stuckNodes }
            val measured = fresh.filter { it.second in 1..MAX_NODE_DELAY_MS }

            // ★ Сами ЗАМЕРЫ в журнал. Без них по строке «вслепую» не отличить две разные
            // болезни: замеры не приходят вовсе (сломан канал до ядра) или приходят, но
            // все нули (проба не может резолвить имя — тот самый замкнутый круг). Лечатся
            // они по-разному, а выглядят в журнале одинаково: в разборе 07–09.08 из 196
            // переключений 155 были вслепую, и понять причину было нечем.
            // Ноль печатаем как «—»: это не «быстро», это «проба провалилась».
            appendLog(
                "замеры: " + pool.joinToString(" ") { (tag, delay) ->
                    val mark = if (tag in stuckNodes) "✗" else ""
                    "$mark$tag=" + (if (delay > 0) "$delay" else "—")
                },
            )

            // Локацию узнаём по тексту после значка: «🛡 BG София» → «BG София».
            val currentPlace = current?.substringAfter(' ')

            val candidate = if (measured.isNotEmpty()) {
                measured.sortedWith(
                    compareBy(
                        // Другой протокол — вперёд (см. выше: обычно ложится протокол целиком).
                        { if (currentKind != null && it.first.firstOrNull() == currentKind) 1 else 0 },
                        { it.second },
                    ),
                ).first().first
            } else {
                // ★ ЗАПАСНОЙ ХОД: замеров нет вообще — идём вслепую, по кругу.
                //
                // Ноль в замере значит «проба провалилась», и в 794 я запретил такие узлы совсем.
                // Это оказалось хуже болезни: когда узел, через который идёт ВЕСЬ трафик, умирает,
                // проба не может даже разрезолвить своё имя — и ноль становится у всех девяти
                // разом. Кандидатов нет, сторож не делает ничего, человек залипает намертво:
                // журнал 06.08 — шесть раз «меняю сервер» и НИ ОДНОГО переключения за четыре часа.
                //
                // Поэтому пробуем вслепую, но не наугад: текущий узел уже в штрафном ящике, так что
                // каждый заход берёт СЛЕДУЮЩИЙ — получается перебор по кругу, а не топтание на
                // одном месте (в 793 «последний рубеж» именно топтался и пять раз возвращал
                // человека на 195.133.14.217). Сначала другая локация: умерший узел чаще всего
                // тянет за собой все свои протоколы, ведь адрес у них общий.
                fresh.sortedWith(
                    compareBy(
                        { if (currentPlace != null && it.first.substringAfter(' ') == currentPlace) 1 else 0 },
                        { if (currentKind != null && it.first.firstOrNull() == currentKind) 1 else 0 },
                    ),
                ).firstOrNull()?.first
            }

            if (candidate != null) {
                // Принудительно на конкретный рабочий узел — уходим с залипшего.
                client.selectOutbound(selector, candidate)
                client.urlTest(selector)
                // Помечаем слепой выбор: при разборе журнала это главный признак того, что замеры
                // встали, — иначе «увёл на …» выглядит одинаково в обоих случаях.
                val how = if (measured.isEmpty()) " · вслепую, замеров нет" else ""
                AppEventLog.log("узел", "увёл на «$candidate» (был «${current ?: "?"}»)$how")
            } else {
                // Узлов не знаем (один сервер / группы ещё не пришли) или все перепробованы —
                // чистим ящик и отдаём выбор автоподбору с чистого листа.
                stuckNodes.clear()
                ProfileTags.auto?.let { client.selectOutbound(selector, it) }
                client.urlTest(selector)
                AppEventLog.log("узел", "все узлы перепробованы — начинаю круг заново")
            }
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

    /**
     * Работает ли САМА сеть телефона, минуя туннель. Сокет создаём фабрикой конкретной сети
     * (`Network.socketFactory`) — тогда пакет уходит через физический интерфейс, а не в tun.
     *
     * Зачем, если есть вердикт системы: он обновляется редко и запаздывает. По журналу 29.07
     * Android держал Wi-Fi «валидным», пока прямые соединения через wlan0 отваливались по
     * таймауту. Своя проба отвечает на вопрос «а сеть-то живая прямо сейчас» и включает ступень
     * «уйти на мобильную» тогда, когда она и нужна.
     */
    private fun underlyingReachable(): Boolean {
        val network = DefaultNetworkMonitor.defaultNetwork ?: return false
        // ★ Несколько целей, а не одна. Проверять только 1.1.1.1 нельзя: в РФ его у многих
        // провайдеров режут, и «сеть телефона мертва» получалось на живой сети — сторож писал
        // «нет интернета, перезапуск не поможет» и НЕ ЧИНИЛ (в журнале 30.07 — 14 раз против 3
        // до правки). Считаем сеть мёртвой, только если недоступны ВСЕ, включая российский DNS.
        for (host in UNDERLYING_PROBE_IPS) {
            val ok = runCatching {
                network.socketFactory.createSocket().use { socket ->
                    socket.connect(
                        InetSocketAddress(InetAddress.getByName(host), PROBE_PORT),
                        UNDERLYING_PROBE_TIMEOUT_MS,
                    )
                    socket.isConnected
                }
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /**
     * Туннель без DNS: соединение до литерального адреса И настоящий обмен пакетами с той стороной.
     *
     * ★ Одного connect() мало. В режиме tun рукопожатие подтверждает САМ sing-box внутри телефона,
     * ещё до того как выяснится, что наружу не ушло ничего. Проба возвращала «туннель жив» на
     * узле, который не пропускал вообще ни байта, — и лестница лечения сворачивала на мягкую
     * ветку «шалит DNS» вместо смены сервера (журнал 06.08: семь часов «DNS не отвечает (туннель
     * жив)» на мёртвом узле). Поэтому доводим до TLS-рукопожатия: оно требует ответа удалённой
     * стороны, локально его подделать нечем.
     */
    private fun tcpReachable(): Boolean = Socket().use { socket ->
        socket.connect(InetSocketAddress(InetAddress.getByName(PROBE_IP), PROBE_PORT), PROBE_TIMEOUT_MS)
        socket.soTimeout = PROBE_TIMEOUT_MS
        // autoClose = false: внешний use() и так закроет сокет, двойное закрытие ни к чему.
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(socket, PROBE_IP, PROBE_PORT, false) as SSLSocket
        ssl.use {
            it.startHandshake()
            true
        }
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

    /**
     * Событие сторожа — в ЕДИНЫЙ журнал (Настройки → Логи), где оно ложится в общую хронологию
     * с сообщениями ядра. Отдельный 30-строчный журнальчик в настройках убран: два журнала в
     * интерфейсе только путали, а этот вдобавок обрезался и жил в SharedPreferences.
     */
    private fun appendLog(message: String) {
        AppEventLog.log("сторож", message)
    }
}
