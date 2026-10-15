package io.nekohasekai.sfa.compose.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.diagnostics.ConnectionDiagnostics
import io.nekohasekai.sfa.diagnostics.ConnectionReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConnectionDiagnosticsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<ConnectionReport?>(null) }
    var hasError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = report?.json()
        if (uri != null && json != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: error("document unavailable")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                hasError = true
            }
        }
    }
    Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.diagnostics_safe_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.diagnostics_safe_description), style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = !busy,
                onClick = {
                    hasError = false
                    report = null
                    busy = true
                    job = scope.launch {
                        try {
                            report = ConnectionDiagnostics.run()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            hasError = true
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.diagnostics_safe_run)) }
            if (busy) {
                Text(stringResource(R.string.diagnostics_safe_running))
                TextButton(onClick = { job?.cancel() }) { Text(stringResource(android.R.string.cancel)) }
            }
            if (hasError) Text(stringResource(R.string.diagnostics_safe_error), color = MaterialTheme.colorScheme.error)
            report?.let { result ->
                Text(stringResource(R.string.diagnostics_safe_result, result.delays.count { it != null }, result.bytes))
                if (result.before != result.after) Text(stringResource(R.string.diagnostics_safe_node_changed))
                Button(onClick = { save.launch("howl-diagnostics.json") }) {
                    Text(stringResource(R.string.diagnostics_safe_save))
                }
            }
        }
    }
}
