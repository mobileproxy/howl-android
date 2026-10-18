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
import io.nekohasekai.sfa.connections.CheckIssue
import io.nekohasekai.sfa.connections.ConnectionChecks
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ConnectionCheckControls(snapshot: CheckSnapshot, phase: CheckPhase, enabled: Boolean,
    start: (Boolean) -> Unit, stop: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.checks_title), style = MaterialTheme.typography.titleSmall)
        val status = when (phase) {
            CheckPhase.Running -> if (snapshot.confirming)
                stringResource(R.string.checks_confirmation_progress, snapshot.retryCompleted, snapshot.retryTotal)
                else stringResource(R.string.checks_first_pass_progress, snapshot.firstPassCompleted, snapshot.total)
            CheckPhase.Done -> if (snapshot.results.isEmpty()) stringResource(R.string.checks_empty)
                else stringResource(R.string.checks_summary_detail, snapshot.allWorking, snapshot.results.size,
                    snapshot.results.values.count { it.state == CheckState.NoResponse }, snapshot.results.values.count { it.state == CheckState.Error })
            CheckPhase.Stopped -> stringResource(R.string.checks_stopped, snapshot.completed, snapshot.total)
            CheckPhase.NetworkChanged -> stringResource(R.string.checks_network_changed)
            CheckPhase.Failed -> stringResource(R.string.checks_failed)
            CheckPhase.Busy -> stringResource(R.string.checks_busy)
            CheckPhase.TimedOut -> stringResource(R.string.checks_time_limit, ConnectionChecks.RUN_DEADLINE_MS / 1000, snapshot.completed, snapshot.total)
            CheckPhase.Idle -> stringResource(R.string.checks_stale)
        }
        Text(status, Modifier.testTag("checks-status"), style = MaterialTheme.typography.bodySmall)
        if (phase == CheckPhase.Running) {
            Text(stringResource(R.string.checks_queue_counts, snapshot.running, snapshot.queued, snapshot.waitingToRetry),
                Modifier.testTag("checks-queues"), style = MaterialTheme.typography.bodySmall)
        }
        if (phase in setOf(CheckPhase.Running, CheckPhase.Done, CheckPhase.Stopped, CheckPhase.TimedOut)) {
            Text(if (phase == CheckPhase.Running) stringResource(R.string.checks_elapsed_limit, snapshot.elapsedMs / 1000, ConnectionChecks.RUN_DEADLINE_MS / 1000)
                else stringResource(R.string.checks_elapsed_done, snapshot.elapsedMs / 1000),
                Modifier.testTag("checks-time"), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.checks_help), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (phase == CheckPhase.Running) {
            LinearProgressIndicator(progress = { snapshot.progressDone.toFloat() / snapshot.progressTotal },
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
    selected: Boolean, selectable: Boolean, select: () -> Unit, modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis()) {
    val status = if (!checkable) stringResource(R.string.checks_routing) else when (result?.state) {
        CheckState.Queued -> stringResource(R.string.checks_queued)
        CheckState.Checking -> attemptText(result, 1, nowMs)
        CheckState.RetryWaiting -> stringResource(R.string.checks_retry_wait)
        CheckState.Retrying -> attemptText(result, 2, nowMs)
        CheckState.Working -> stringResource(R.string.checks_working, result.delayMs ?: 0)
        CheckState.NoResponse -> stringResource(R.string.checks_no_response)
        CheckState.Error -> stringResource(R.string.checks_error)
        CheckState.Cancelled -> stringResource(R.string.checks_cancelled)
        CheckState.NotCompleted -> stringResource(R.string.checks_not_completed)
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
            val hint = when (result?.state) {
                CheckState.NoResponse -> R.string.checks_no_response_hint
                CheckState.NotCompleted -> R.string.checks_deadline_hint
                CheckState.RetryWaiting -> R.string.checks_retry_after_first
                CheckState.Error -> if (result.issue == CheckIssue.RequestDidNotStop) R.string.checks_request_stalled else R.string.checks_error_hint
                else -> null
            }
            if (hint != null) {
                Text(stringResource(hint),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun attemptText(result: CheckResult?, attempt: Int, nowMs: Long): String {
    val started = result?.startedAt
    val timeout = result?.timeoutMs
    if (started == null || timeout == null) return stringResource(R.string.checks_attempt, attempt, 2)
    val budget = ((timeout + 999) / 1000).coerceAtLeast(1)
    val elapsed = ((nowMs - started) / 1000).coerceIn(0, budget)
    return stringResource(R.string.checks_attempt_progress, attempt, elapsed, budget)
}
