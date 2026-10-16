package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.connections.CheckPhase
import io.nekohasekai.sfa.connections.CheckResult
import io.nekohasekai.sfa.connections.CheckSnapshot
import io.nekohasekai.sfa.connections.CheckState
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ConnectionCheckControls(snapshot: CheckSnapshot, phase: CheckPhase, enabled: Boolean,
    start: (Boolean) -> Unit, stop: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.checks_title), style = MaterialTheme.typography.titleSmall)
        val status = when (phase) {
            CheckPhase.Running -> stringResource(R.string.checks_progress, snapshot.completed, snapshot.total, snapshot.working, snapshot.repeating)
            CheckPhase.Done -> if (snapshot.results.isEmpty()) stringResource(R.string.checks_empty)
                else stringResource(R.string.checks_summary, snapshot.allWorking, snapshot.results.size)
            CheckPhase.Stopped -> stringResource(R.string.checks_stopped, snapshot.completed, snapshot.total)
            CheckPhase.NetworkChanged -> stringResource(R.string.checks_network_changed)
            CheckPhase.Failed -> stringResource(R.string.checks_failed)
            CheckPhase.Busy -> stringResource(R.string.checks_busy)
            CheckPhase.Idle -> stringResource(R.string.checks_stale)
        }
        Text(status, Modifier.testTag("checks-status"), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.checks_help), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (phase == CheckPhase.Running) {
            LinearProgressIndicator(progress = { if (snapshot.total == 0) 0f else snapshot.completed.toFloat() / snapshot.total },
                modifier = Modifier.fillMaxWidth().testTag("checks-progress"))
            TextButton(onClick = stop, modifier = Modifier.testTag("checks-stop")) { Text(stringResource(R.string.checks_stop)) }
        } else {
            if (snapshot.unresolved.isNotEmpty()) {
                TextButton(onClick = { start(true) }, enabled = enabled, modifier = Modifier.testTag("checks-retry")) {
                    Text(stringResource(R.string.checks_retry))
                }
            }
            TextButton(onClick = { start(false) }, enabled = enabled, modifier = Modifier.testTag("checks-start")) {
                Text(stringResource(R.string.checks_again))
            }
        }
    }
}

@Composable
internal fun ConnectionCheckRow(name: String, protocol: String, result: CheckResult?, checkable: Boolean,
    selected: Boolean, selectable: Boolean, select: () -> Unit, modifier: Modifier = Modifier) {
    val status = if (!checkable) stringResource(R.string.checks_routing) else when (result?.state) {
        CheckState.Queued -> stringResource(R.string.checks_queued)
        CheckState.Checking -> stringResource(R.string.checks_attempt, 1, 2)
        CheckState.RetryWaiting -> stringResource(R.string.checks_retry_wait)
        CheckState.Retrying -> stringResource(R.string.checks_attempt, 2, 2)
        CheckState.Working -> stringResource(R.string.checks_working, result.delayMs ?: 0)
        CheckState.NoResponse -> stringResource(R.string.checks_no_response)
        CheckState.Error -> stringResource(R.string.checks_error)
        CheckState.Cancelled -> stringResource(R.string.checks_cancelled)
        null -> stringResource(R.string.checks_stale)
    }
    Surface(onClick = select, enabled = selectable, modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            if (protocol.isNotEmpty()) Text(protocol, style = MaterialTheme.typography.labelSmall)
            val color = when (result?.state) {
                CheckState.Working -> MaterialTheme.colorScheme.primary
                CheckState.NoResponse -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(status, style = MaterialTheme.typography.labelMedium, color = color)
            result?.checkedAt?.let {
                Text(stringResource(R.string.checks_checked_at, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it))),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (result?.state == CheckState.NoResponse || result?.state == CheckState.Error) {
                Text(stringResource(if (result.state == CheckState.NoResponse) R.string.checks_no_response_hint else R.string.checks_error_hint),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
