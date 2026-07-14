package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.component.RemoteControlMenuItems
import io.nekohasekai.sfa.compose.component.rememberRemoteServers
import io.nekohasekai.sfa.compose.navigation.NewProfileArgs
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.launch

data class CardRenderItem(val cards: List<CardGroup>, val isRow: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    serviceStatus: Status = Status.Stopped,
    showStartFab: Boolean = false,
    showStatusBar: Boolean = false,
    onOpenNewProfile: (NewProfileArgs) -> Unit = {},
    onOpenServers: () -> Unit = {},
    viewModel: DashboardViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val remoteServer by RemoteControlManager.remoteServer.collectAsState()
    val remoteConnected by RemoteControlManager.isConnected.collectAsState()
    val isRemote = remoteServer != null
    val remoteServers by rememberRemoteServers()
    var showOthersMenu by remember { mutableStateOf(false) }

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.title_dashboard)) },
            actions = {
                Box {
                    IconButton(onClick = { showOthersMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.title_others),
                        )
                    }
                    DropdownMenu(
                        expanded = showOthersMenu,
                        onDismissRequest = { showOthersMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dashboard_items)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.GridView,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            onClick = {
                                showOthersMenu = false
                                viewModel.toggleCardSettingsDialog()
                            },
                        )
                        RemoteControlMenuItems(
                            servers = remoteServers,
                            onAction = { showOthersMenu = false },
                        )
                    }
                }
            },
        )
    }

    // Update service status in ViewModel
    LaunchedEffect(serviceStatus) {
        viewModel.updateServiceStatus(serviceStatus)
    }

    // Events are now handled globally in ComposeActivity via GlobalEventBus

    // Show deprecated notes dialog
    if (uiState.showDeprecatedDialog && uiState.deprecatedNotes.isNotEmpty()) {
        val note = uiState.deprecatedNotes.first()
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.error_deprecated_warning)) },
            text = { Text(note.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissDeprecatedNote() }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton =
            if (!note.migrationLink.isNullOrBlank()) {
                {
                    TextButton(onClick = {
                        viewModel.sendGlobalEvent(UiEvent.OpenUrl(note.migrationLink))
                        viewModel.dismissDeprecatedNote()
                    }) {
                        Text(stringResource(R.string.error_deprecated_documentation))
                    }
                }
            } else {
                null
            },
        )
    }

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    // Show dashboard settings bottom sheet
    if (uiState.showCardSettingsDialog) {
        DashboardSettingsBottomSheet(
            sheetState = sheetState,
            visibleCards = uiState.visibleCards,
            cardOrder = uiState.cardOrder,
            onToggleCard = viewModel::toggleCardVisibility,
            onReorderCards = viewModel::reorderCards,
            onResetOrder = viewModel::resetCardOrder,
            onDismiss = {
                scope.launch {
                    sheetState.hide()
                    viewModel.closeCardSettingsDialog()
                }
            },
        )
    }

    // Server picker (reused by the Howl home server selector)
    if (uiState.showProfilePickerSheet) {
        ProfilePickerSheet(
            profiles = uiState.profiles,
            selectedProfileId = uiState.selectedProfileId,
            onProfileSelected = { profile -> viewModel.selectProfile(profile.id) },
            onProfileEdit = viewModel::editProfile,
            onProfileDelete = viewModel::deleteProfile,
            onProfileMove = viewModel::moveProfile,
            onDismiss = viewModel::hideProfilePickerSheet,
        )
    }

    if (isRemote && !remoteConnected) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (isRemote) {
        // Remote control keeps the classic card dashboard.
        val bottomPadding = when {
            showStartFab -> 88.dp
            showStatusBar -> 74.dp
            else -> 0.dp
        }
        DashboardCards(
            cards = visibleDashboardCards(uiState, isRemote = true, excludeProfiles = false),
            uiState = uiState,
            serviceStatus = serviceStatus,
            viewModel = viewModel,
            onOpenNewProfile = onOpenNewProfile,
            contentPadding = PaddingValues(bottom = bottomPadding),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        )
        return
    }

    HowlHomeContent(
        uiState = uiState,
        serviceStatus = serviceStatus,
        viewModel = viewModel,
        onOpenNewProfile = onOpenNewProfile,
        onOpenServers = onOpenServers,
    )
}

/**
 * Howl home screen: a big connect button, live status, and the current-server selector.
 * Any cards the user re-enables from the ⋮ menu appear between the button and the selector.
 */
@Composable
private fun HowlHomeContent(
    uiState: DashboardUiState,
    serviceStatus: Status,
    viewModel: DashboardViewModel,
    onOpenNewProfile: (NewProfileArgs) -> Unit,
    onOpenServers: () -> Unit,
) {
    val hasProfile = uiState.selectedProfileId != -1L
    val serverName = if (hasProfile) uiState.selectedProfileName else null

    val statusLabel = when (serviceStatus) {
        Status.Started -> stringResource(R.string.howl_status_connected)
        Status.Starting -> stringResource(R.string.howl_status_connecting)
        Status.Stopping -> stringResource(R.string.howl_status_disconnecting)
        Status.Stopped -> stringResource(R.string.howl_status_disconnected)
    }
    val hintText = when {
        !hasProfile -> stringResource(R.string.howl_hint_add_server)
        serviceStatus == Status.Started -> stringResource(R.string.howl_hint_tap_disconnect)
        serviceStatus == Status.Stopped -> stringResource(R.string.howl_hint_tap_connect)
        else -> ""
    }

    val localCards = visibleDashboardCards(uiState, isRemote = false, excludeProfiles = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HowlConnectButton(
                    status = serviceStatus,
                    onClick = {
                        if (hasProfile) {
                            viewModel.toggleService()
                        } else {
                            onOpenNewProfile(NewProfileArgs())
                        }
                    },
                )

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (serviceStatus == Status.Started) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Center,
                )

                if (hintText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (localCards.isNotEmpty()) {
            DashboardCards(
                cards = localCards,
                uiState = uiState,
                serviceStatus = serviceStatus,
                viewModel = viewModel,
                onOpenNewProfile = onOpenNewProfile,
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        HowlServerSelector(
            serverName = serverName,
            onClick = {
                when {
                    // Connected with a server group → pick the location within the subscription.
                    serviceStatus == Status.Started && uiState.hasGroups -> onOpenServers()
                    uiState.profiles.isEmpty() -> onOpenNewProfile(NewProfileArgs())
                    else -> viewModel.showProfilePickerSheet()
                }
            },
            label = stringResource(R.string.howl_server),
            emptyTitle = stringResource(R.string.howl_no_server),
            emptyHint = stringResource(R.string.howl_hint_add_server),
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
    }
}

/**
 * Compute the set of cards that should actually render, given availability and service state.
 */
fun visibleDashboardCards(uiState: DashboardUiState, isRemote: Boolean, excludeProfiles: Boolean): Set<CardGroup> {
    val serviceRunning = uiState.isStatusVisible
    return uiState.visibleCards.filter { cardGroup ->
        when {
            isRemote ->
                cardGroup != CardGroup.Profiles &&
                    cardGroup != CardGroup.SystemProxy &&
                    serviceRunning &&
                    isCardAvailableWhenServiceRunning(cardGroup, uiState)

            cardGroup == CardGroup.Profiles -> !excludeProfiles
            else -> serviceRunning && isCardAvailableWhenServiceRunning(cardGroup, uiState)
        }
    }.toSet()
}

/**
 * The classic card list, extracted so both the remote dashboard and the Howl home screen
 * (when the user re-enables cards) share one renderer.
 */
@Composable
private fun DashboardCards(
    cards: Set<CardGroup>,
    uiState: DashboardUiState,
    serviceStatus: Status,
    viewModel: DashboardViewModel,
    onOpenNewProfile: (NewProfileArgs) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val cardRenderItems =
        processCardsForRendering(
            cardOrder = uiState.cardOrder,
            visibleCards = cards,
            cardWidths = uiState.cardWidths,
        )

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = contentPadding,
    ) {
        items(cardRenderItems) { renderItem ->
            if (renderItem.isRow && renderItem.cards.size >= 2) {
                // Render two half-width cards in a row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    renderItem.cards.forEach { cardGroup ->
                        DashboardCardRenderer(
                            cardGroup = cardGroup,
                            cardWidth =
                            uiState.cardWidths[cardGroup]
                                ?: CardWidth.Full,
                            uiState = uiState,
                            onClashModeSelected = viewModel::selectClashMode,
                            onSystemProxyToggle = viewModel::toggleSystemProxy,
                            // Profile card specific props
                            profiles = uiState.profiles,
                            selectedProfileId = uiState.selectedProfileId,
                            isLoading = uiState.isLoading,
                            showAddProfileSheet = uiState.showAddProfileSheet,
                            showProfilePickerSheet = uiState.showProfilePickerSheet,
                            updatingProfileId = uiState.updatingProfileId,
                            updatedProfileId = uiState.updatedProfileId,
                            onProfileSelected = viewModel::selectProfile,
                            onProfileEdit = viewModel::editProfile,
                            onProfileDelete = viewModel::deleteProfile,
                            onProfileShare = viewModel::shareProfile,
                            onProfileShareURL = viewModel::shareProfileURL,
                            onProfileUpdate = viewModel::updateProfile,
                            onProfileMove = viewModel::moveProfile,
                            onShowAddProfileSheet = viewModel::showAddProfileSheet,
                            onHideAddProfileSheet = viewModel::hideAddProfileSheet,
                            onShowProfilePickerSheet = viewModel::showProfilePickerSheet,
                            onHideProfilePickerSheet = viewModel::hideProfilePickerSheet,
                            onOpenNewProfile = onOpenNewProfile,
                            commandClient = viewModel.commandClient,
                            modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    }
                }
            } else {
                // Render single card (full-width or single half-width)
                renderItem.cards.forEach { cardGroup ->
                    DashboardCardRenderer(
                        cardGroup = cardGroup,
                        cardWidth =
                        uiState.cardWidths[cardGroup]
                            ?: CardWidth.Full,
                        uiState = uiState,
                        serviceStatus = serviceStatus,
                        onClashModeSelected = viewModel::selectClashMode,
                        onSystemProxyToggle = viewModel::toggleSystemProxy,
                        // Profile card specific props
                        profiles = uiState.profiles,
                        selectedProfileId = uiState.selectedProfileId,
                        isLoading = uiState.isLoading,
                        showAddProfileSheet = uiState.showAddProfileSheet,
                        showProfilePickerSheet = uiState.showProfilePickerSheet,
                        updatingProfileId = uiState.updatingProfileId,
                        updatedProfileId = uiState.updatedProfileId,
                        onProfileSelected = viewModel::selectProfile,
                        onProfileEdit = viewModel::editProfile,
                        onProfileDelete = viewModel::deleteProfile,
                        onProfileShare = viewModel::shareProfile,
                        onProfileShareURL = viewModel::shareProfileURL,
                        onProfileUpdate = viewModel::updateProfile,
                        onProfileMove = viewModel::moveProfile,
                        onShowAddProfileSheet = viewModel::showAddProfileSheet,
                        onHideAddProfileSheet = viewModel::hideAddProfileSheet,
                        onShowProfilePickerSheet = viewModel::showProfilePickerSheet,
                        onHideProfilePickerSheet = viewModel::hideProfilePickerSheet,
                        onOpenNewProfile = onOpenNewProfile,
                        commandClient = viewModel.commandClient,
                    )
                }
            }
        }
    }
}

/**
 * Process cards for rendering, grouping consecutive half-width cards into rows
 */
fun processCardsForRendering(
    cardOrder: List<CardGroup>,
    visibleCards: Set<CardGroup>,
    cardWidths: Map<CardGroup, CardWidth>,
): List<CardRenderItem> {
    val renderItems = mutableListOf<CardRenderItem>()
    val visibleOrderedCards = cardOrder.filter { visibleCards.contains(it) }

    var i = 0
    while (i < visibleOrderedCards.size) {
        val currentCard = visibleOrderedCards[i]
        val currentWidth = cardWidths[currentCard] ?: CardWidth.Full

        if (currentWidth == CardWidth.Half) {
            // Check if next card is also half-width
            if (i + 1 < visibleOrderedCards.size) {
                val nextCard = visibleOrderedCards[i + 1]
                val nextWidth = cardWidths[nextCard] ?: CardWidth.Full

                if (nextWidth == CardWidth.Half) {
                    // Group two half-width cards together
                    renderItems.add(
                        CardRenderItem(
                            cards = listOf(currentCard, nextCard),
                            isRow = true,
                        ),
                    )
                    i += 2
                    continue
                }
            }
            // Single half-width card
            renderItems.add(
                CardRenderItem(
                    cards = listOf(currentCard),
                    isRow = false,
                ),
            )
        } else {
            // Full-width card
            renderItems.add(
                CardRenderItem(
                    cards = listOf(currentCard),
                    isRow = false,
                ),
            )
        }
        i++
    }

    return renderItems
}

/**
 * Determine if a service-dependent card has data available to display.
 * This function is only relevant when the service is running.
 * Note: Profiles card is always available and should not use this function.
 */
fun isCardAvailableWhenServiceRunning(cardGroup: CardGroup, uiState: DashboardUiState): Boolean = when (cardGroup) {
    CardGroup.ClashMode -> uiState.clashModeVisible
    CardGroup.UploadTraffic -> uiState.trafficVisible
    CardGroup.DownloadTraffic -> uiState.trafficVisible
    CardGroup.Debug -> true // Debug info is always available when service is running
    CardGroup.Connections -> uiState.trafficVisible
    CardGroup.SystemProxy -> uiState.systemProxyVisible
    CardGroup.Profiles -> true // This shouldn't be called for Profiles, but return true for safety
}
