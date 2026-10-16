package io.nekohasekai.sfa.compose.screen.dashboard.groups

import androidx.lifecycle.viewModelScope
import io.nekohasekai.sfa.connections.CheckSnapshot
import io.nekohasekai.sfa.connections.CheckPhase
import io.nekohasekai.sfa.connections.CheckTarget
import io.nekohasekai.sfa.connections.ConnectionChecksController
import io.nekohasekai.sfa.connections.LibboxProbeTransport
import io.nekohasekai.sfa.bg.DefaultNetworkListener
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.NonCancellable
import io.nekohasekai.sfa.constant.SettingsKey
import io.nekohasekai.sfa.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.sfa.compose.base.BaseViewModel
import io.nekohasekai.sfa.compose.base.ScreenEvent
import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.compose.model.GroupItem
import io.nekohasekai.sfa.compose.model.toList
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GroupsUiState(
    val groups: List<Group> = emptyList(),
    val isLoading: Boolean = false,
    val expandedGroups: Set<String> = emptySet(),
    val showCloseConnectionsSnackbar: Boolean = false,
    val checks: CheckSnapshot = CheckSnapshot(),
    val checkPhase: CheckPhase = CheckPhase.Idle,
    val checksAvailable: Boolean = false,
)

sealed class GroupsEvent : ScreenEvent {
    data class GroupSelected(val groupTag: String, val itemTag: String) : GroupsEvent()
}

class GroupsViewModel(private val sharedCommandClient: CommandClient? = null) :
    BaseViewModel<GroupsUiState, GroupsEvent>(),
    CommandClient.Handler {
    private val commandClient: CommandClient
    private val isUsingSharedClient: Boolean

    private val _serviceStatus = MutableStateFlow(Status.Stopped)
    val serviceStatus = _serviceStatus.asStateFlow()
    private var lastServiceStatus: Status = Status.Stopped

    private val checks = ConnectionChecksController(viewModelScope,
        measureFactory = { val transport = LibboxProbeTransport.create(); { tag, attempt -> transport.measure(tag, attempt) } },
        publish = { snapshot, phase -> updateState { copy(checks = snapshot, checkPhase = phase) } })
    private var panelCount = 0
    private var checkWhenReady = false
    private var networkKnown = false
    private var lastNetwork: android.net.Network? = null
    private val groupEpoch = java.util.concurrent.atomic.AtomicLong()
    private var lastRemoteId: Long? = null
    private val profileListener = object : OnPreferenceDataStoreChangeListener {
        override fun onPreferenceDataStoreChanged(store: androidx.preference.PreferenceDataStore, key: String) {
            if (key == SettingsKey.SELECTED_PROFILE) {
                groupEpoch.incrementAndGet()
                viewModelScope.launch {
                    checks.invalidate()
                    updateState { copy(groups = emptyList()) }
                    checkWhenReady = panelCount > 0
                }
            }
        }
    }

    fun panelShown() {
        panelCount++
        if (panelCount == 1) { checkWhenReady = true; checkIfReady() }
    }
    fun panelHidden() {
        panelCount = (panelCount - 1).coerceAtLeast(0)
        if (panelCount == 0) { checkWhenReady = false; checks.stop() }
    }
    private fun canCheck() = AppLifecycleObserver.isForeground.value &&
        if (CommandTarget.isRemote) RemoteControlManager.isConnected.value else _serviceStatus.value == Status.Started
    private fun updateCheckTargets() {
        val groups = uiState.value.groups
        val selected = groups.filter { it.selectable }.ifEmpty { groups }
        checks.updateTargets(selected.flatMap { group -> group.items.map { CheckTarget(it.tag, it.type) } },
            Settings.selectedProfile to CommandTarget.remoteServer?.id)
    }
    private fun checkIfReady() {
        if (!checkWhenReady || panelCount == 0 || !canCheck() || uiState.value.groups.isEmpty()) return
        checkWhenReady = false; updateCheckTargets(); checks.start()
    }
    fun startConnectionChecks(retryOnly: Boolean = false) {
        if (!canCheck() || panelCount == 0) return
        updateCheckTargets(); checks.start(retryOnly)
    }
    fun stopConnectionChecks() = checks.stop()

    init {
        Settings.dataStore.registerChangeListener(profileListener)
        viewModelScope.launch {
            DefaultNetworkListener.start(this@GroupsViewModel) { network ->
                viewModelScope.launch {
                    if (networkKnown && lastNetwork != network) checks.invalidate(networkChanged = true)
                    networkKnown = true; lastNetwork = network
                }
            }
        }
        if (sharedCommandClient != null) {
            commandClient = sharedCommandClient
            isUsingSharedClient = true
            commandClient.addHandler(this)
        } else {
            commandClient =
                CommandClient(
                    viewModelScope,
                    CommandClient.ConnectionType.Groups,
                    this,
                )
            isUsingSharedClient = false
        }

        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                RemoteControlManager.remoteServer,
                RemoteControlManager.isConnected,
                _serviceStatus,
            ) { foreground, remoteServer, remoteConnected, status ->
                SessionTarget(
                    connect = foreground &&
                        if (remoteServer != null) remoteConnected else status == Status.Started,
                    remoteServerId = remoteServer?.id,
                )
            }.distinctUntilChanged().collect { target ->
                if (lastRemoteId != target.remoteServerId) {
                    lastRemoteId = target.remoteServerId
                    checks.invalidate()
                    checkWhenReady = panelCount > 0
                }
                updateState { copy(checksAvailable = target.connect) }
                if (target.connect) {
                    if (isUsingSharedClient) {
                        commandClient.addHandler(this@GroupsViewModel)
                    } else {
                        updateState { copy(isLoading = true) }
                        commandClient.connect()
                    }
                } else {
                    checks.stop()
                    if (isUsingSharedClient) {
                        commandClient.removeHandler(this@GroupsViewModel)
                    } else {
                        commandClient.disconnect()
                    }
                }
            }
        }
    }

    private data class SessionTarget(val connect: Boolean, val remoteServerId: Long?)

    override fun createInitialState() = GroupsUiState()

    override fun onCleared() {
        checks.stop()
        Settings.dataStore.unregisterChangeListener(profileListener)
        viewModelScope.launch(NonCancellable) { DefaultNetworkListener.stop(this@GroupsViewModel) }
        super.onCleared()
        if (isUsingSharedClient) {
            commandClient.removeHandler(this)
        } else {
            commandClient.disconnect()
        }
    }

    private fun handleServiceStatusChange(status: Status) {
        if (RemoteControlManager.remoteServer.value != null) {
            return
        }
        if (status != Status.Started) {
            checks.invalidate()
            updateState {
                copy(
                    checksAvailable = false,
                    groups = emptyList(),
                    isLoading = false,
                )
            }
        }
    }

    fun updateServiceStatus(status: Status) {
        if (status == lastServiceStatus) {
            return
        }
        lastServiceStatus = status
        viewModelScope.launch {
            _serviceStatus.emit(status)
            handleServiceStatusChange(status)
        }
    }

    fun toggleGroupExpand(groupTag: String) {
        val newExpanded = !uiState.value.expandedGroups.contains(groupTag)
        updateState {
            val newExpandedGroups = if (newExpanded) {
                expandedGroups + groupTag
            } else {
                expandedGroups - groupTag
            }
            copy(expandedGroups = newExpandedGroups)
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                CommandTarget.standaloneClient().setGroupExpand(groupTag, newExpanded)
            }
        }
    }

    fun toggleAllGroups() {
        val groups = uiState.value.groups
        val allCollapsed = uiState.value.expandedGroups.isEmpty()
        val newExpanded = allCollapsed

        updateState {
            if (allCollapsed) {
                copy(expandedGroups = groups.map { it.tag }.toSet())
            } else {
                copy(expandedGroups = emptySet())
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            groups.forEach { group ->
                runCatching {
                    CommandTarget.standaloneClient().setGroupExpand(group.tag, newExpanded)
                }
            }
        }
    }

    fun selectGroupItem(groupTag: String, itemTag: String) {
        checks.stop()
        // Check if this is actually a different selection
        val currentGroup = uiState.value.groups.find { it.tag == groupTag }
        if (currentGroup?.selected == itemTag) {
            // Same item selected, no need to do anything
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Select the new outbound immediately
                CommandTarget.standaloneClient().selectOutbound(groupTag, itemTag)

                // ★ Рвём открытые соединения — но ТОЛЬКО здесь, при РУЧНОМ выборе человека.
                // Раньше это делал сам селектор (interrupt_exist_connections=true в подписке),
                // и потому обрыв случался и при АВТОМАТИЧЕСКОЙ смене узла сторожем: журнал
                // 19–20.08 — 93 переключения за сутки, то есть загрузки и звонки рвались каждые
                // несколько минут. Флаг снят, разрыв перенесён сюда: человек переключает сервер
                // осознанно и ждёт, что старые соединения передоговорятся через новый, иначе
                // получается «переключил, а не помогло» — keep-alive продолжает идти через
                // мёртвый узел. Автоматика теперь меняет узел бесшовно.
                runCatching { CommandTarget.standaloneClient().closeConnections() }

                // Update local state and show snackbar
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            groups =
                            groups.map { group ->
                                if (group.tag == groupTag) {
                                    group.copy(selected = itemTag)
                                } else {
                                    group
                                }
                            },
                            showCloseConnectionsSnackbar = true,
                        )
                    }
                    sendEvent(GroupsEvent.GroupSelected(groupTag, itemTag))
                }
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    fun closeConnections() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                CommandTarget.standaloneClient().closeConnections()
                withContext(Dispatchers.Main) {
                    dismissCloseConnectionsSnackbar()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dismissCloseConnectionsSnackbar()
                }
                sendError(e)
            }
        }
    }

    fun dismissCloseConnectionsSnackbar() {
        updateState {
            copy(showCloseConnectionsSnackbar = false)
        }
    }

    fun urlTest(@Suppress("UNUSED_PARAMETER") groupTag: String) = startConnectionChecks()

    // CommandClient.Handler implementation
    override fun onConnected() {
        val epoch = groupEpoch.incrementAndGet()
        viewModelScope.launch(Dispatchers.Main) {
            if (epoch != groupEpoch.get()) return@launch
            checks.invalidate()
            checkWhenReady = panelCount > 0
            updateState { copy(checksAvailable = canCheck()) }
            // Connection established, waiting for groups
        }
    }

    override fun onDisconnected() {
        groupEpoch.incrementAndGet()
        viewModelScope.launch(Dispatchers.Main) {
            checks.invalidate()
            updateState {
                copy(
                    checksAvailable = false,
                    groups = emptyList(),
                    isLoading = false,
                )
            }
        }
    }

    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
        val epoch = groupEpoch.get()
        val profile = Settings.selectedProfile
        viewModelScope.launch(Dispatchers.Default) {
            val currentGroups = uiState.value.groups
            val newGroupsMap = newGroups.associateBy { it.tag }

            // Smart merge: preserve existing Group objects when only delays change
            val mergedGroups =
                if (currentGroups.isEmpty()) {
                    // Initial load
                    newGroups.map(::Group)
                } else {
                    currentGroups.mapNotNull { existingGroup ->
                        val newGroupData = newGroupsMap[existingGroup.tag]
                        if (newGroupData != null) {
                            // Check if only delays have changed
                            val newItems = newGroupData.items.toList()
                            val hasStructuralChange =
                                existingGroup.items.size != newItems.size ||
                                    existingGroup.selected != newGroupData.selected ||
                                    existingGroup.type != newGroupData.type ||
                                    existingGroup.selectable != newGroupData.selectable

                            if (hasStructuralChange) {
                                // Structural change, create new Group
                                Group(newGroupData)
                            } else {
                                // Only delays might have changed, update items efficiently
                                val updatedItems =
                                    existingGroup.items.mapIndexed { index, item ->
                                        val newItemData = newItems.getOrNull(index)
                                        if (newItemData != null &&
                                            item.tag == newItemData.tag &&
                                            item.type == newItemData.type
                                        ) {
                                            // Only update if delay actually changed
                                            if (item.urlTestDelay != newItemData.urlTestDelay ||
                                                item.urlTestTime != newItemData.urlTestTime
                                            ) {
                                                GroupItem(newItemData)
                                            } else {
                                                item // Keep existing object
                                            }
                                        } else {
                                            if (newItemData != null) {
                                                GroupItem(newItemData)
                                            } else {
                                                item // Keep existing if index out of bounds
                                            }
                                        }
                                    }
                                existingGroup.copy(items = updatedItems)
                            }
                        } else {
                            null
                        }
                    } +
                        newGroups.filter { newGroup ->
                            currentGroups.none { it.tag == newGroup.tag }
                        }.map(::Group)
                }

            withContext(Dispatchers.Main) {
                if (epoch != groupEpoch.get() || profile != Settings.selectedProfile) return@withContext
                updateState {
                    val initialExpandedGroups = if (expandedGroups.isEmpty() && currentGroups.isEmpty()) {
                        mergedGroups.filter { it.isExpand }.map { it.tag }.toSet()
                    } else {
                        expandedGroups
                    }
                    copy(
                        groups = mergedGroups,
                        expandedGroups = initialExpandedGroups,
                        isLoading = false,
                    )
                }
                updateCheckTargets()
                checkIfReady()
            }
        }
    }
}
