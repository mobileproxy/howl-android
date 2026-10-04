package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.subscription.NodeSites

/**
 * Правила сторожа, которые проверяются без телефона и без сети: куда уводить с залипшего
 * узла и когда можно проверять связь снова. Вынесены из ConnectivityWatchdog (он привязан к
 * Application и ядру), чтобы их держали юнит-тесты. Порт правил Windows-клиента 1.0.27.
 */
object WatchdogRules {

    /**
     * Нижняя граница от запуска сторожа до первой проверки, даже если его разбудила смена сети.
     * 04.10.2026 на Windows сторож проверил связь через 2 с после перезапуска ядра и ещё раз
     * через секунду — и сменил рабочий узел.
     */
    const val MIN_FIRST_CHECK_MS = 10_000L

    /** Пока идёт сбой, проверки разнесены минимум на столько от КОНЦА прошлой проверки. */
    const val MIN_FAILING_RECHECK_MS = 10_000L

    /** Совпавшие источники (будильник, цикл, события) — вторую проверку пропускаем. */
    const val MIN_CHECK_SPACING_MS = 5_000L

    /** Сколько ещё ждать до первой проверки после запуска сторожа. */
    fun firstCheckWaitMs(sinceStartMs: Long): Long = (MIN_FIRST_CHECK_MS - sinceStartMs).coerceAtLeast(0L)

    /**
     * Пропустить ли проверку. Три случая: сторож только что запущен; только что начиналась
     * другая проверка; идёт сбой, а прошлая проверка закончилась меньше 10 с назад — иначе
     * «отказы подряд» сыпались бы пачкой от событий сети, а не были разнесены во времени.
     */
    fun skipCheck(sinceStartMs: Long, sinceLastStartMs: Long, sinceLastEndMs: Long, failing: Boolean): Boolean =
        sinceStartMs < MIN_FIRST_CHECK_MS ||
            sinceLastStartMs < MIN_CHECK_SPACING_MS ||
            (failing && sinceLastEndMs < MIN_FAILING_RECHECK_MS)

    /**
     * Порядок кандидатов для карусели: сперва ДРУГОЙ сервер, затем другой протокол, затем
     * задержка. «Подозрительны» адреса и протоколы всех штрафных узлов и текущего: раз там не
     * держалось, сосед по адресу или по протоколу — худшая ставка, чем чужой.
     *
     * Соседи не исключаются: если живы только они, взять соседа лучше, чем остаться на мёртвом.
     * Медленный чужой сервер выигрывает у быстрого соседа — задержку мерили короткой пробой.
     * Задержка 0 и меньше — «не измерено», в конец; при равенстве — по имени, чтобы слепой
     * перебор шёл по кругу, а не топтался.
     */
    fun order(
        candidates: List<Pair<String, Int>>,
        sites: Map<String, NodeSites.Site>,
        penalized: Collection<String>,
    ): List<String> {
        val badHosts = HashSet<String>()
        val badProtos = HashSet<String>()
        for (p in penalized) {
            val s = sites[p] ?: continue
            badHosts.add(s.host.lowercase())
            badProtos.add(s.proto.lowercase())
        }
        return candidates
            .sortedWith(
                compareBy<Pair<String, Int>>(
                    { if (sites[it.first]?.let { s -> s.host.lowercase() in badHosts } == true) 1 else 0 },
                    { if (sites[it.first]?.let { s -> s.proto.lowercase() in badProtos } == true) 1 else 0 },
                    { if (it.second > 0) it.second else Int.MAX_VALUE },
                    { it.first },
                ),
            )
            .map { it.first }
    }
}
