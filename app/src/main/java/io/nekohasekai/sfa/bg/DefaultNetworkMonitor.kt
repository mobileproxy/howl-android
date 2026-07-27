package io.nekohasekai.sfa.bg

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.sfa.Application
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

object DefaultNetworkMonitor {

    /**
     * Сколько сеть считается плохой после того, как через неё не пошёл трафик.
     *
     * Без этого срока починка не держалась: сторож уводил на мобильную, но `defaultNetwork`
     * оставался прежним, и первый же системный колбэк возвращал нас на тот же мёртвый Wi-Fi.
     * В журнале с устройства 24.07 это видно прямо: в 16:28 ушли на rmnet_data1, а весь
     * остаток сеанса — снова `dial wlan0 (27): no route to host`, сотнями.
     */
    private const val BAD_NETWORK_PENALTY_MS = 3 * 60_000L

    var defaultNetwork: Network? = null
    private var listener: InterfaceUpdateListener? = null

    /** Сети, через которые только что не пошёл трафик, и когда мы это заметили. */
    private val badNetworks = ConcurrentHashMap<Network, Long>()

    /** Заявка на мобильную сеть — держим, только пока чиним. См. [requestCellularBackup]. */
    @Volatile
    private var cellularRequest: ConnectivityManager.NetworkCallback? = null

    /**
     * Дёргается при смене сети (Wi-Fi ↔ мобильная). Ядро при этом переключает только НОВЫЕ
     * соединения, а уже открытые остаются привязаны к исчезнувшему интерфейсу и молча умирают —
     * отсюда «подключено, но трафик не идёт». Сторож использует это, чтобы проверить связь
     * сразу, а не ждать очередной минутной проверки.
     */
    @Volatile
    var onNetworkChanged: (() -> Unit)? = null

    suspend fun start() {
        DefaultNetworkListener.start(this) {
            val changed = defaultNetwork != it
            defaultNetwork = it
            checkDefaultInterfaceUpdate(it)
            if (changed) runCatching { onNetworkChanged?.invoke() }
        }
        defaultNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Application.connectivity.activeNetwork
        } else {
            DefaultNetworkListener.get()
        }
    }

    suspend fun stop() {
        releaseCellularBackup()
        badNetworks.clear()
        DefaultNetworkListener.stop(this)
    }

    suspend fun require(): Network {
        val network = defaultNetwork
        if (network != null) {
            return network
        }
        return DefaultNetworkListener.get()
    }

    fun setListener(listener: InterfaceUpdateListener?) {
        this.listener = listener
        checkDefaultInterfaceUpdate(defaultNetwork)
    }

    /**
     * Сети, через которые в принципе можно выйти наружу: с интернетом и не наши собственные
     * VPN-интерфейсы.
     *
     * Порядок: сначала те, что НЕ в штрафном ящике, внутри — те, что система считает рабочими
     * (validated; именно этой проверкой Android рисует «!» на значке Wi-Fi). Штрафные не
     * выбрасываем совсем — если других нет, лучше плохая сеть, чем никакой.
     *
     * Одного `validated` мало: система снимает этот признак не сразу и может держать его на
     * Wi-Fi, который уже отвечает «no route to host». Поэтому свой вердикт (штрафной ящик)
     * стоит выше системного.
     */
    private fun usableNetworks(): List<Network> {
        val cm = Application.connectivity
        return runCatching {
            cm.allNetworks
                .mapNotNull { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                    Triple(
                        network,
                        !isPenalized(network),
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    )
                }
                .sortedWith(
                    compareByDescending<Triple<Network, Boolean, Boolean>> { it.second }
                        .thenByDescending { it.third },
                )
                .map { it.first }
        }.getOrDefault(emptyList())
    }

    /** Сеть в штрафном ящике? Просроченные записи заодно убираем. */
    private fun isPenalized(network: Network): Boolean {
        val since = badNetworks[network] ?: return false
        if (System.currentTimeMillis() - since > BAD_NETWORK_PENALTY_MS) {
            badNetworks.remove(network)
            return false
        }
        return true
    }

    /**
     * Пометить сеть, через которую только что не пошёл трафик. Зовёт сторож, когда пробы не
     * прошли: это НАШ вердикт по факту, а не мнение системы, — и он важнее, потому что система
     * своё «validated» снимает с опозданием или не снимает вовсе.
     */
    fun markCurrentNetworkBad() {
        val network = reportedNetwork ?: defaultNetwork ?: return
        badNetworks[network] = System.currentTimeMillis()
    }

    /** Забыть все штрафы — связь восстановилась, прошлые вердикты больше не актуальны. */
    fun clearPenalties() = badNetworks.clear()

    /**
     * Есть ли куда уходить — рабочая сеть, отличная от текущей.
     *
     * Сторож спрашивает это, чтобы отличить «телефон вцепился в мёртвый Wi-Fi, а рядом живая
     * мобильная» (чиним) от «интернета нет вообще» (чинить нечем, ждём). Раньше эти два случая
     * не различались, и оба заканчивались бездействием.
     */
    fun hasAlternativeNetwork(): Boolean {
        val current = reportedNetwork ?: defaultNetwork
        return usableNetworks().any { it != current }
    }

    /**
     * Попросить систему поднять мобильную сеть.
     *
     * Зачем. Пока телефон подключён к Wi-Fi, Android гасит модем ради батареи, и мобильной
     * сети в списке просто НЕТ. Из-за этого «уйти на другую сеть» было некуда: и обычный
     * выбор, и принудительный `preferAlternative` возвращали тот же мёртвый Wi-Fi — менять
     * было не на что. Эта заявка поднимает модем, не трогая выбор системной «главной» сети.
     *
     * Держим заявку только на время сбоя: поднятый модем ест батарею, а трафик через него —
     * мобильные мегабайты. [releaseCellularBackup] зовётся, как только связь вернулась.
     */
    fun requestCellularBackup() {
        if (cellularRequest != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {}
        cellularRequest = callback
        runCatching {
            Application.connectivity.requestNetwork(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
        }.onFailure { cellularRequest = null }
    }

    fun releaseCellularBackup() {
        val callback = cellularRequest ?: return
        cellularRequest = null
        runCatching { Application.connectivity.unregisterNetworkCallback(callback) }
    }

    /**
     * Какую сеть отдать ядру.
     *
     * Раньше отдавали ту, что система назначила главной, — и этого хватало ровно до случая,
     * когда телефон держится за мёртвый Wi-Fi: ядро привязывалось к нему, все протоколы разом
     * упирались в «no route to host», а перезагрузка конфига не помогала, потому что отдавали
     * ТУ ЖЕ мёртвую сеть. Теперь выбираем работающую: если главная не проходит проверку
     * системы, а рядом есть живая (обычно мобильная) — уходим на неё автоматически.
     *
     * @param preferAlternative просит намеренно взять ДРУГУЮ сеть, чем сейчас: так сторож
     * уводит нас с сети, которая по всем признакам «жива», но трафик через неё не идёт.
     */
    private fun pickNetwork(preferred: Network?, preferAlternative: Boolean): Network? {
        val cm = Application.connectivity
        val usable = usableNetworks()
        if (usable.isEmpty()) return preferred

        if (preferAlternative) {
            usable.firstOrNull { it != preferred }?.let { return it }
            return preferred
        }

        // Системная «главная» устраивает, только если она реально работает: система не
        // возражает И мы сами не видели через неё отказов (штрафной ящик). Без второй половины
        // проверки любой колбэк возвращал нас на мёртвый Wi-Fi сразу после ухода с него.
        val preferredOk = preferred != null && !isPenalized(preferred) && runCatching {
            cm.getNetworkCapabilities(preferred)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }.getOrDefault(false)
        if (preferredOk) return preferred

        return usable.firstOrNull() ?: preferred
    }

    /**
     * Пересобрать выбор сети и сообщить его ядру. Зовётся при смене сети и сторожем, когда
     * трафик встал: пере-выбор + перезагрузка конфига возвращают связь без участия человека.
     */
    fun reevaluate(preferAlternative: Boolean = false) {
        // При принудительном уходе отталкиваемся от сети, которую РЕАЛЬНО отдали ядру: системная
        // «главная» после прошлого ухода осталась прежней, и «любая, кроме неё» вернула бы нас
        // ровно туда, откуда мы только что ушли.
        val from = if (preferAlternative) reportedNetwork ?: defaultNetwork else defaultNetwork
        checkDefaultInterfaceUpdate(from, preferAlternative)
    }

    private fun checkDefaultInterfaceUpdate(newNetwork: Network?, preferAlternative: Boolean = false) {
        val listener = listener ?: return
        val target = pickNetwork(newNetwork, preferAlternative)
        if (target != null) {
            for (times in 0 until 10) {
                val linkProperties = Application.connectivity.getLinkProperties(target)
                if (linkProperties == null) {
                    Thread.sleep(100)
                    continue
                }
                var interfaceIndex: Int
                try {
                    interfaceIndex = NetworkInterface.getByName(linkProperties.interfaceName).index
                } catch (e: Exception) {
                    Thread.sleep(100)
                    continue
                }
                // Отдаём интерфейс ядру. Пишем в журнал только смену — иначе засорим повторами.
                if (reportedInterface != linkProperties.interfaceName) {
                    io.nekohasekai.sfa.utils.AppEventLog.log(
                        "сеть",
                        "интерфейс → ${linkProperties.interfaceName}",
                    )
                }
                reportedInterface = linkProperties.interfaceName
                reportedNetwork = target
                listener.updateDefaultInterface(linkProperties.interfaceName, interfaceIndex, false, false)
                // Цикл — это ретраи на случай, если свойства сети ещё не готовы. Данные получены,
                // повторять нечего: без выхода ядро дёргалось 10 раз подряд на каждое событие сети.
                return
            }
        } else {
            reportedInterface = null
            reportedNetwork = null
            listener.updateDefaultInterface("", -1, false, false)
        }
    }

    /** Имя интерфейса, который сейчас отдан ядру, — для журнала сторожа. */
    @Volatile
    var reportedInterface: String? = null
        private set

    /**
     * Сеть, которая сейчас реально отдана ядру. Это НЕ всегда [defaultNetwork]: после
     * принудительного ухода на другую сеть системная «главная» остаётся прежней. Штрафовать и
     * сравнивать надо именно эту — иначе штраф уходил бы не на ту сеть, через которую сейчас
     * идёт (точнее, не идёт) трафик.
     */
    @Volatile
    private var reportedNetwork: Network? = null
}
