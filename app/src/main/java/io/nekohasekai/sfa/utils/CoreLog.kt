package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.Application
import org.json.JSONObject
import java.io.File

/**
 * Постоянный журнал ядра — переживает переподключения.
 *
 * Экран «Логи» показывает только то, что ядро отдало по живому соединению: при каждом старте
 * буфер начинается заново, и как раз момент обрыва в него не попадает. Поэтому просим само ядро
 * писать в файл (`log.output`) — файл копится через остановки и запуски, и его можно отправить.
 *
 * Сбоев тут быть не должно: при любой ошибке возвращаем конфиг как есть, логи не стоят того,
 * чтобы из-за них не поднялся VPN.
 */
object CoreLog {

    private const val FILE_NAME = "howl-core.log"
    private const val PREV_NAME = "howl-core.log.1"
    private const val MAX_BYTES = 2L * 1024 * 1024

    fun file(): File = File(Application.application.filesDir, FILE_NAME)

    fun previousFile(): File = File(Application.application.filesDir, PREV_NAME)

    fun sizeBytes(): Long = runCatching {
        (if (file().exists()) file().length() else 0L) +
            (if (previousFile().exists()) previousFile().length() else 0L)
    }.getOrDefault(0L)

    fun clear() {
        runCatching { file().delete() }
        runCatching { previousFile().delete() }
    }

    /**
     * Оставляем ровно два поколения файла: текущее и предыдущее. Так журнал и не растёт
     * бесконечно, и история переживает несколько переподключений подряд.
     */
    private fun rotateIfNeeded() {
        runCatching {
            val current = file()
            if (current.exists() && current.length() > MAX_BYTES) {
                val previous = previousFile()
                previous.delete()
                current.renameTo(previous)
            }
        }
    }

    /**
     * Прописывает в конфиг вывод логов в файл. Уровень поднимаем до info: warn (как приходит
     * с сервера) молчит ровно про то, что нужно для разбора обрывов.
     */
    fun apply(content: String): String = runCatching {
        rotateIfNeeded()
        val root = JSONObject(content)
        val log = root.optJSONObject("log") ?: JSONObject().also { root.put("log", it) }
        log.put("output", file().absolutePath)
        log.put("timestamp", true)
        if (log.optString("level").lowercase() in setOf("", "panic", "fatal", "error", "warn")) {
            log.put("level", "info")
        }
        root.toString()
    }.getOrDefault(content)
}
