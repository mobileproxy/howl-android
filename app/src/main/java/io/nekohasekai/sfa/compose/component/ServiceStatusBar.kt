package io.nekohasekai.sfa.compose.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.health.HealthRuntime
import io.nekohasekai.sfa.health.HealthSnapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.delay

@Composable
fun ServiceStatusBar(
    visible: Boolean,
    serviceStatus: Status,
    startTime: Long?,
    groupsCount: Int,
    hasGroups: Boolean,
    onGroupsClick: () -> Unit,
    connectionsCount: Int,
    onConnectionsClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = rememberConnectionHealthSnapshot()
    val remote by RemoteControlManager.remoteServer.collectAsState()
    val health = connectionHealthText(serviceStatus, snapshot, Settings.watchdogEnabled, remote != null)
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Status text
                StatusItem(
                    health = health,
                    modifier = Modifier.weight(1f),
                )

                // Connections button
                Row(
                    modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable(onClick = onConnectionsClick)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = connectionsCount.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Outlined.Cable,
                        contentDescription = stringResource(R.string.title_connections),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }

                // Groups button (only show if hasGroups)
                if (hasGroups) {
                    Row(
                        modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable(onClick = onGroupsClick)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = groupsCount.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Outlined.Folder,
                            contentDescription = stringResource(R.string.title_groups),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                // Stop button
                Row(
                    modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(onClick = onStopClick)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (startTime != null) {
                        UptimeText(startTime = startTime)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Icon(
                        imageVector = Icons.Outlined.Stop,
                        contentDescription = stringResource(R.string.stop),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

/** Re-read elapsedRealtime after sleep; no periodic UI or background polling. */
@Composable
fun rememberConnectionHealthSnapshot(): HealthSnapshot {
    val snapshot by HealthRuntime.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) HealthRuntime.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) HealthRuntime.refresh()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // A resumed composition may precede the effect. Never draw an expired success.
    val current = HealthRuntime.view()
    return if (current != snapshot) current else snapshot
}

@Composable
private fun StatusItem(health: ConnectionHealthText, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(health.label),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = if (health.verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(health.hints.first()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun UptimeText(startTime: Long, modifier: Modifier = Modifier) {
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(startTime) {
        while (true) {
            delay(1000)
            currentTime = System.currentTimeMillis()
        }
    }

    val elapsedSeconds = ((currentTime - startTime) / 1000).coerceAtLeast(0)
    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60

    val formattedTime =
        if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }

    Text(
        text = formattedTime,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier,
    )
}
