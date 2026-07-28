package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.Application
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал СОБЫТИЙ приложения — то, что ядро само не пишет: старт с версией и устройством, смены
 * сети, решения сторожа, выбор узла. Пишется из Kotlin в свой файл, а на экране «Журнал»
 * сливается с журналом ядра по времени в единую хронологию.
 *
 * Зачем отдельный файл, а не общий с ядром: ядро (Go) пишет в свой файл через `log.output`,
 * влезать туда конкурентной записью из Kotlin — гонки. Проще держать раздельно и объединять
 * при просмотре.
 *
 * ★ Информативность добавляем ИМЕННО так — дешёвыми событиями со стороны приложения. Поднимать
 * уровень ядра до info НЕЛЬЗЯ: там per-packet lookup, он душит туннель (см. CoreLog).
 *
 * Формат строки совпадает с ядром: «+0300 2026-07-27 11:15:30 …» — тогда слияние по времени
 * тривиально (сортировка по началу строки).
 */
object AppEventLog {

    private const val FILE_NAME = "howl-events.log"
    private const val PREV_NAME = "howl-events.log.1"
    private const val MAX_BYTES = 512L * 1024

    // Длина префикса времени «+0300 2026-07-27 11:15:30» — по нему сортируем при слиянии.
    private const val STAMP_LEN = 25

    private val stampFormat = SimpleDateFormat("Z yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun file(): File = File(Application.application.filesDir, FILE_NAME)
    private fun previousFile(): File = File(Application.application.filesDir, PREV_NAME)

    // Живая трансляция новых событий: экран «Логи» показывает их сразу, вместе со строками ядра.
    // Событий единицы в минуту, поэтому небольшого буфера с запасом достаточно; tryEmit не
    // блокирует и безопасен из любого потока (log() зовут и из сервиса, и из сторожа).
    private val _stream = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    val stream: SharedFlow<String> = _stream.asSharedFlow()

    /**
     * Записать событие. category — короткая метка источника («старт», «сеть», «сторож», «узел»),
     * попадает в строку, чтобы в общем журнале было видно, откуда событие.
     */
    @Synchronized
    fun log(category: String, message: String) {
        runCatching {
            rotateIfNeeded()
            val line = "${stampFormat.format(Date())} EVENT [$category] $message"
            file().appendText("$line\n")
            _stream.tryEmit(line)
        }
    }

    /** Уже записанные события (обе генерации файла) — история для экрана «Логи». */
    fun history(): List<String> = readAll().lineSequence().filter { it.isNotBlank() }.toList()

    private fun rotateIfNeeded() {
        runCatching {
            val current = file()
            if (current.exists() && current.length() > MAX_BYTES) {
                previousFile().delete()
                current.renameTo(previousFile())
            }
        }
    }

    fun clear() {
        runCatching { file().delete() }
        runCatching { previousFile().delete() }
    }

    fun sizeBytes(): Long = runCatching {
        (if (file().exists()) file().length() else 0L) +
            (if (previousFile().exists()) previousFile().length() else 0L)
    }.getOrDefault(0L)

    private fun readAll(): String = buildString {
        runCatching { if (previousFile().exists()) append(previousFile().readText()) }
        runCatching { if (file().exists()) append(file().readText()) }
    }

    /**
     * Слить журнал ядра и журнал событий в одну хронологию. Обе стороны пишут строки, начинающиеся
     * с одинакового штампа времени, поэтому сортируем по первым [STAMP_LEN] символам. Строки-
     * продолжения (не начинаются со штампа, напр. многострочная ошибка ядра) приклеиваем к
     * предыдущей записи, чтобы не оторвались при сортировке.
     */
    fun merged(): String {
        val core = runCatching {
            (if (CoreLog.previousFile().exists()) CoreLog.previousFile().readText() else "") +
                (if (CoreLog.file().exists()) CoreLog.file().readText() else "")
        }.getOrDefault("")
        val events = readAll()

        val records = mutableListOf<Pair<String, String>>() // (ключ сортировки, полный текст записи)
        for (source in listOf(core, events)) {
            for (line in source.lineSequence()) {
                if (line.isEmpty()) continue
                val hasStamp = line.startsWith("+") && line.length >= STAMP_LEN
                if (hasStamp || records.isEmpty()) {
                    records.add((if (hasStamp) line.substring(0, STAMP_LEN) else "") to line)
                } else {
                    // Продолжение предыдущей записи.
                    val last = records.removeAt(records.size - 1)
                    records.add(last.first to (last.second + "\n" + line))
                }
            }
        }
        // Стабильная сортировка по времени: записи с одинаковым штампом сохраняют исходный порядок.
        return records.sortedBy { it.first }.joinToString("\n") { it.second }
    }
}
