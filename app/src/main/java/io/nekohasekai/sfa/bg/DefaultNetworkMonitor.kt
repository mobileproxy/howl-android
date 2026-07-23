package io.nekohasekai.sfa.bg

import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.sfa.Application
import java.net.NetworkInterface

object DefaultNetworkMonitor {

    var defaultNetwork: Network? = null
    private var listener: InterfaceUpdateListener? = null

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
     * VPN-интерфейсы. Сначала те, которые система считает РАБОЧИМИ (validated) — именно этой
     * проверкой Android рисует «!» на значке Wi-Fi.
     */
    private fun usableNetworks(): List<Network> {
        val cm = Application.connectivity
        return runCatching {
            cm.allNetworks
                .mapNotNull { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                    network to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
                .sortedByDescending { it.second }
                .map { it.first }
        }.getOrDefault(emptyList())
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

        // Системная «главная» устраивает, только если она реально работает.
        val preferredOk = preferred != null && runCatching {
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
        checkDefaultInterfaceUpdate(defaultNetwork, preferAlternative)
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
                reportedInterface = linkProperties.interfaceName
                listener.updateDefaultInterface(linkProperties.interfaceName, interfaceIndex, false, false)
                // Цикл — это ретраи на случай, если свойства сети ещё не готовы. Данные получены,
                // повторять нечего: без выхода ядро дёргалось 10 раз подряд на каждое событие сети.
                return
            }
        } else {
            reportedInterface = null
            listener.updateDefaultInterface("", -1, false, false)
        }
    }

    /** Имя интерфейса, который сейчас отдан ядру, — для журнала сторожа. */
    @Volatile
    var reportedInterface: String? = null
        private set
}
