package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.SplitTunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTunnelScreen(navController: NavController, serviceStatus: Status = Status.Stopped) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.split_tunnel)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.content_description_back),
                    )
                }
            },
        )
    }

    val scope = rememberCoroutineScope()
    val notifyApplyChange = rememberApplyServiceChangeNotifier(serviceStatus)

    var text by remember { mutableStateOf(Settings.splitTunnelDomains) }
    // Показываем, сколько строк реально распозналось как домены — иначе опечатка
    // молча проглатывается и человек думает, что правило работает.
    val parsedCount = remember(text) { SplitTunnel.parseDomains(text).size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.split_tunnel_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    label = { Text(stringResource(R.string.split_tunnel_domains_label)) },
                    placeholder = { Text("sberbank.ru\ngosuslugi.ru") },
                    supportingText = {
                        Text(stringResource(R.string.split_tunnel_recognized, parsedCount))
                    },
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                Settings.splitTunnelDomains = text
                            }
                            // Правила подмешиваются при старте ядра, поэтому нужен перезапуск.
                            notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Restart)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.split_tunnel_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}
