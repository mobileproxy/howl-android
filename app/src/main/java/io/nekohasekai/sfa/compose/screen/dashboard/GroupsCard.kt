package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.ExperimentalFoundationApi
import io.nekohasekai.sfa.connections.CheckSnapshot
import io.nekohasekai.sfa.connections.CheckTarget
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compat.LazyColumnCompat
import io.nekohasekai.sfa.compat.rememberOverscrollEffectCompat
import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.compose.model.GroupItem
import io.nekohasekai.sfa.compose.screen.dashboard.groups.GroupsViewModel
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.compose.util.rememberSheetDismissFromContentOnlyIfGestureStartedAtTopModifier
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.CommandClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsCard(
    serviceStatus: Status,
    commandClient: CommandClient? = null,
    viewModel: GroupsViewModel? = null,
    showTopBar: Boolean = false,
    listHeaderContent: (@Composable () -> Unit)? = null,
    asSheet: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val actualViewModel: GroupsViewModel = viewModel ?: viewModel(
        factory =
        object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return GroupsViewModel(commandClient) as T
            }
        },
    )
    val snackbarHostState = remember { SnackbarHostState() }
    val uiState by actualViewModel.uiState.collectAsState()
    DisposableEffect(actualViewModel) {
        actualViewModel.panelShown()
        onDispose { actualViewModel.panelHidden() }
    }

    if (showTopBar) {
        val allCollapsed = uiState.expandedGroups.isEmpty()
        OverrideTopBar {
            TopAppBar(
                title = { Text(stringResource(R.string.title_groups)) },
                actions = {
                    if (uiState.groups.isNotEmpty()) {
                        IconButton(onClick = { actualViewModel.toggleAllGroups() }) {
                            Icon(
                                imageVector =
                                if (allCollapsed) {
                                    Icons.Default.UnfoldMore
                                } else {
                                    Icons.Default.UnfoldLess
                                },
                                contentDescription =
                                if (allCollapsed) {
                                    stringResource(R.string.expand_all)
                                } else {
                                    stringResource(R.string.collapse_all)
                                },
                            )
                        }
                    }
                },
            )
        }
    }

    // Stable callbacks to prevent recomposition - use remember with viewModel as key
    val onToggleExpanded =
        remember(actualViewModel) {
            { groupTag: String -> actualViewModel.toggleGroupExpand(groupTag) }
        }
    val onItemSelected =
        remember(actualViewModel) {
            { groupTag: String, itemTag: String -> actualViewModel.selectGroupItem(groupTag, itemTag) }
        }
    val onUrlTest =
        remember(actualViewModel) {
            { groupTag: String -> actualViewModel.urlTest(groupTag) }
        }

    // Only update service status when it actually changes
    LaunchedEffect(serviceStatus) {
        actualViewModel.updateServiceStatus(serviceStatus)
    }

    // Show snackbar when needed. Строки берём заранее — stringResource нельзя звать внутри
    // LaunchedEffect (не @Composable), а хардкод «Close all connections?» не переводился.
    val closeConnectionsMessage = stringResource(R.string.close_connections_confirm)
    val closeActionLabel = stringResource(R.string.close)
    LaunchedEffect(uiState.showCloseConnectionsSnackbar) {
        if (uiState.showCloseConnectionsSnackbar) {
            val result =
                snackbarHostState.showSnackbar(
                    message = closeConnectionsMessage,
                    actionLabel = closeActionLabel,
                    duration = androidx.compose.material3.SnackbarDuration.Indefinite,
                    withDismissAction = true,
                )
            when (result) {
                androidx.compose.material3.SnackbarResult.ActionPerformed -> {
                    actualViewModel.closeConnections()
                }

                androidx.compose.material3.SnackbarResult.Dismissed -> {
                    actualViewModel.dismissCloseConnectionsSnackbar()
                }
            }
        }
    }

    GroupsCardContent(
        uiState = uiState,
        onToggleExpanded = onToggleExpanded,
        onItemSelected = onItemSelected,
        onUrlTest = onUrlTest,
        onCheckStart = actualViewModel::startConnectionChecks,
        onCheckStop = actualViewModel::stopConnectionChecks,
        listHeaderContent = listHeaderContent,
        asSheet = asSheet,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupsCardContent(
    uiState: io.nekohasekai.sfa.compose.screen.dashboard.groups.GroupsUiState,
    onToggleExpanded: (String) -> Unit,
    onItemSelected: (String, String) -> Unit,
    onUrlTest: (String) -> Unit,
    onCheckStart: (Boolean) -> Unit,
    onCheckStop: () -> Unit,
    listHeaderContent: (@Composable () -> Unit)? = null,
    asSheet: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Группа автоподбора (urltest) перечисляет те же серверы, что и селектор, и не кликается —
    // читается как дубль профиля. Пинги не теряются: они рисуются у каждого пункта внутри
    // оставшейся группы. Если селектора нет вовсе, показываем список как есть.
    val visibleGroups = remember(uiState.groups) {
        if (uiState.groups.any { it.selectable }) {
            uiState.groups.filter { it.selectable }
        } else {
            uiState.groups
        }
    }
    val lazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val scrollModifier =
        if (asSheet) {
            rememberSheetDismissFromContentOnlyIfGestureStartedAtTopModifier {
                lazyListState.firstVisibleItemIndex == 0 &&
                    lazyListState.firstVisibleItemScrollOffset == 0
            }
        } else {
            Modifier.nestedScroll(rememberBounceBlockingNestedScrollConnection(lazyListState))
        }
    val overscrollEffect = if (asSheet) null else rememberOverscrollEffectCompat()

    LazyColumnCompat(
        modifier =
        modifier
            .fillMaxSize()
            .then(scrollModifier),
        state = lazyListState,
        contentPadding =
        PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        overscrollEffect = overscrollEffect,
    ) {
        if (listHeaderContent != null) {
            item(key = "groups_list_header") {
                listHeaderContent()
            }
        }

        stickyHeader(key = "connection_check_controls") {
            Surface(color = MaterialTheme.colorScheme.surface) {
                ConnectionCheckControls(uiState.checks, uiState.checkPhase, uiState.checksAvailable,
                    onCheckStart, onCheckStop)
            }
        }
        when {
            uiState.isLoading -> {
                item(key = "groups_loading") {
                    Box(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            visibleGroups.isEmpty() -> {
                item(key = "groups_empty") {
                    Box(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.no_groups),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> {
                items(
                    items = visibleGroups,
                    key = { it.tag },
                    contentType = { "GroupCard" },
                ) { group ->
                    ProxyGroupItem(
                        group = group,
                        isExpanded = uiState.expandedGroups.contains(group.tag),
                        onToggleExpanded = { onToggleExpanded(group.tag) },
                        onItemSelected = { itemTag -> onItemSelected(group.tag, itemTag) },
                        onUrlTest = { onUrlTest(group.tag) },
                        checks = uiState.checks,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyGroupItem(
    group: Group,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onItemSelected: (String) -> Unit,
    onUrlTest: () -> Unit,
    checks: CheckSnapshot,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Header (clickable to expand/collapse)
            Surface(
                onClick = onToggleExpanded,
                color = Color.Transparent,
            ) {
                ListItem(
                    headlineContent = {
                        Column {
                            Text(
                                text = group.tag,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = Libbox.proxyDisplayType(group.type),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                // Show selected item when collapsed
                                AnimatedVisibility(
                                    visible = !isExpanded && group.selected.isNotEmpty(),
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = "•",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = group.selected,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    },
                    trailingContent = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Expand/Collapse indicator
                            val rotationAngle by animateFloatAsState(
                                targetValue = if (isExpanded) 180f else 0f,
                                animationSpec = tween(300),
                                label = "ExpandIcon",
                            )

                            Icon(
                                imageVector = Icons.Default.ExpandMore,
                                contentDescription = if (isExpanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                                modifier =
                                Modifier
                                    .size(24.dp)
                                    .graphicsLayer { rotationZ = rotationAngle },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )
            }

            // Expandable content
            AnimatedVisibility(
                visible = isExpanded && group.items.isNotEmpty(),
                enter =
                expandVertically(animationSpec = tween(300)) +
                    fadeIn(
                        animationSpec =
                        tween(
                            300,
                        ),
                    ),
                exit =
                shrinkVertically(animationSpec = tween(300)) +
                    fadeOut(
                        animationSpec =
                        tween(
                            300,
                        ),
                    ),
            ) {
                Column {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        thickness = 1.dp,
                    )

                    // Proxy Items
                    ProxyItemsList(
                        items = group.items,
                        selectedTag = group.selected,
                        isSelectable = group.selectable,
                        onItemSelected = onItemSelected,
                        checks = checks,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyItemsList(items: List<GroupItem>, selectedTag: String, isSelectable: Boolean,
    onItemSelected: (String) -> Unit, checks: CheckSnapshot) {
    val ordered = remember(items.map { it.tag to it.type }) {
        items.sortedBy { CheckTarget(it.tag, it.type).location.lowercase() }
    }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ordered.forEach { item ->
            key(item.tag) {
                ConnectionCheckRow(item.tag, protocolLabel(item), checks.results[item.tag],
                    CheckTarget(item.tag, item.type).checkable, item.tag == selectedTag, isSelectable,
                    select = { onItemSelected(item.tag) }, nowMs = checks.clockAt)
            }
        }
    }
}

@Composable
private fun rememberBounceBlockingNestedScrollConnection(lazyListState: LazyListState): NestedScrollConnection = remember(lazyListState) {
    object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Only block upward scroll (y < 0) at bottom to prevent sheet expansion
            // Allow downward scroll (y > 0) at top to let sheet collapse
            return if (available.y < 0) available else Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            // Only block upward fling (y < 0) to prevent sheet expansion
            // Allow downward fling (y > 0) to let sheet collapse
            return if (available.y < 0) available else Velocity.Zero
        }
    }
}

/**
 * Название протокола для второй строки карточки узла.
 *
 * Ядро знает только ТИП выхода, а у ShadowTLS он тот же самый `shadowsocks`: внутри
 * маскировки действительно он и работает. Из-за этого в списке локаций ДВЕ РАЗНЫЕ строки
 * подписывались одинаково — «Shadowsocks» и «Shadowsocks», — хотя это разные способы
 * подключения на разных портах: 741 голым потоком и 742 под видом обычного сайта.
 *
 * Наши собственные теги несут точное имя после разделителя («🎭 BG София · ShadowTLS»),
 * поэтому берём его оттуда — это единственный источник, который знает про маскировку.
 * У чужих профилей разделителя нет, и тогда работает прежний путь через ядро.
 */
private fun protocolLabel(item: GroupItem): String {
    val suffix = item.tag.substringAfterLast(" · ", "").trim()
    return if (suffix.isNotEmpty()) suffix else item.type
}
