package io.nekohasekai.sfa.compose.screen.log

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.sfa.compose.util.AnsiColorUtils
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.AppEventLog
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedList

class LogViewModel :
    BaseLogViewModel(),
    CommandClient.Handler {
    companion object {
        private val maxLines = 3000
    }

    private val bufferedLogs = LinkedList<ProcessedLogEntry>()
    private val commandClient =
        CommandClient(
            scope = viewModelScope,
            connectionType = CommandClient.ConnectionType.Log,
            handler = this,
        )
    private var lastServiceStatus: Status = Status.Stopped
    private val serviceStatusFlow = MutableStateFlow(Status.Stopped)

    init {
        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                RemoteControlManager.remoteServer,
                RemoteControlManager.isConnected,
                serviceStatusFlow,
            ) { foreground, remoteServer, remoteConnected, status ->
                SessionTarget(
                    connect = foreground &&
                        if (remoteServer != null) remoteConnected else status == Status.Started,
                    remoteServerId = remoteServer?.id,
                )
            }.distinctUntilChanged().collect { target ->
                if (target.connect) {
                    commandClient.connect()
                } else {
                    commandClient.disconnect()
                }
            }
        }

        // События приложения (старт, смена сети, решения сторожа, выбор узла) — в ТОТ ЖЕ журнал:
        // сперва история из файла, затем живая трансляция. Раньше они жили только в «Автопочинке»
        // и в выгрузке «Поделиться», поэтому журнал на экране фактически оставался раздвоенным:
        // ядро отдельно, действия приложения отдельно.
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) { AppEventLog.history() }
            if (history.isNotEmpty()) {
                addProcessed(history.map(::eventEntry))
            }
            AppEventLog.stream.collect { line ->
                addProcessed(listOf(eventEntry(line)))
            }
        }
    }

    /** Строка события приложения в виде записи журнала (уровень Info — это не ошибки ядра). */
    private fun eventEntry(line: String): ProcessedLogEntry = ProcessedLogEntry(
        id = logIdGenerator.incrementAndGet(),
        entry = LogEntryData(level = LogLevel.INFO, message = line),
        annotatedString = AnsiColorUtils.ansiToAnnotatedString(line),
    )

    private data class SessionTarget(val connect: Boolean, val remoteServerId: Long?)

    private fun processLogEntry(entry: LogEntry): ProcessedLogEntry {
        val level = LogLevel.entries.find { it.priority == entry.level } ?: LogLevel.Default
        return ProcessedLogEntry(
            id = logIdGenerator.incrementAndGet(),
            entry = LogEntryData(level = level, message = entry.message),
            annotatedString = AnsiColorUtils.ansiToAnnotatedString(entry.message),
        )
    }

    override fun updateServiceStatus(status: Status) {
        lastServiceStatus = status
        serviceStatusFlow.value = status
        _uiState.update { it.copy(serviceStatus = status) }

        if (RemoteControlManager.remoteServer.value != null) {
            return
        }
        when (status) {
            Status.Stopped, Status.Stopping -> {
                _uiState.update { it.copy(isConnected = false) }
            }

            else -> {}
        }
    }

    override fun onConnected() {
        _uiState.update { it.copy(isConnected = true) }
    }

    override fun onDisconnected() {
        _uiState.update { it.copy(isConnected = false) }
    }

    override fun setDefaultLogLevel(level: Int) {
        val logLevel = LogLevel.entries.find { it.priority == level } ?: error("Unknown log level: $level")
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.update { it.copy(defaultLogLevel = logLevel) }
            updateDisplayedLogs()
        }
    }

    override fun clearLogs() {
        viewModelScope.launch(Dispatchers.Main) {
            allLogs.clear()
            bufferedLogs.clear()
            _uiState.update { it.copy(isPaused = false) }
            updateDisplayedLogs()
        }
    }

    override fun requestClearLogs() {
        viewModelScope.launch {
            val sent =
                withContext(Dispatchers.IO) {
                    runCatching {
                        CommandTarget.standaloneClient().clearLogs()
                    }.isSuccess
                }
            // With the service stopped there is no broadcast to clear the UI,
            // so the local buffer is cleared directly.
            if (!sent) {
                clearLogs()
            }
        }
    }

    override fun appendLogs(message: List<LogEntry>) {
        addProcessed(message.map { processLogEntry(it) })
    }

    /** Общий путь добавления записей (ядро и события приложения): пауза, лимит строк, автоскролл. */
    private fun addProcessed(processedLogs: List<ProcessedLogEntry>) {
        viewModelScope.launch(Dispatchers.Main) {
            if (_uiState.value.isPaused) {
                bufferedLogs.addAll(processedLogs)
            } else {
                val totalSize = allLogs.size + processedLogs.size
                val removeCount = (totalSize - maxLines).coerceAtLeast(0)

                if (removeCount > 0) {
                    repeat(removeCount) {
                        allLogs.removeFirst()
                    }
                }

                allLogs.addAll(processedLogs)
                updateDisplayedLogs()

                if (_autoScrollEnabled.value && !_uiState.value.isPaused && !_uiState.value.isSearchActive) {
                    scrollToBottom()
                }
            }
        }
    }

    override fun togglePause() {
        val currentState = _uiState.value
        if (currentState.isPaused && bufferedLogs.isNotEmpty()) {
            val totalSize = allLogs.size + bufferedLogs.size
            val removeCount = (totalSize - maxLines).coerceAtLeast(0)

            if (removeCount > 0) {
                repeat(removeCount) {
                    allLogs.removeFirst()
                }
            }

            allLogs.addAll(bufferedLogs)
            bufferedLogs.clear()
        }

        _uiState.update { it.copy(isPaused = !it.isPaused) }
        updateDisplayedLogs()
    }

    override fun onCleared() {
        super.onCleared()
        commandClient.disconnect()
    }
}
