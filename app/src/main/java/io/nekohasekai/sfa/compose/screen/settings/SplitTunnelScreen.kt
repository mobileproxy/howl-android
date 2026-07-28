package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.SaveBar
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

    var text by remember { mutableStateOf(Settings.splitTunnelDomains) }
    var mode by remember { mutableIntStateOf(Settings.splitTunnelMode) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    val modeOptions = listOf(
        SplitTunnel.MODE_EXCLUDE to stringResource(R.string.split_tunnel_mode_exclude),
        SplitTunnel.MODE_INCLUDE to stringResource(R.string.split_tunnel_mode_include),
    )

    // Показываем сами распознанные домены, а не их количество: опечатку («ozon,ru»)
    // так видно сразу, и понятно, что именно уйдёт мимо туннеля.
    val parsed = SplitTunnel.parseDomains(text)

    val msgApplied = stringResource(R.string.split_tunnel_saved_applied)
    val msgLater = stringResource(R.string.split_tunnel_saved_later)
    val msgManual = stringResource(R.string.split_tunnel_saved_manual)
    val recognizedPrefix = stringResource(R.string.split_tunnel_recognized_prefix)
    val recognizedNone = stringResource(R.string.split_tunnel_recognized_none)

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
                Spacer(modifier = Modifier.height(8.dp))

                // Выбор режима: список как «мимо VPN» (дефолт) или как «только через VPN».
                modeOptions.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                mode = value
                                savedMessage = null
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = mode == value,
                            onClick = {
                                mode = value
                                savedMessage = null
                            },
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                // Режим whitelist переворачивает логику: всё, кроме списка, идёт МИМО VPN.
                // Об этом важно предупредить — иначе человек думает, что защищён целиком.
                if (mode == SplitTunnel.MODE_INCLUDE) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.split_tunnel_include_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        savedMessage = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    label = { Text(stringResource(R.string.split_tunnel_domains_label)) },
                    placeholder = { Text("ozon.ru\nsberbank.ru") },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (parsed.isEmpty()) {
                        recognizedNone
                    } else {
                        recognizedPrefix + " " + parsed.joinToString(", ")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (parsed.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                SaveBar(
                    savedMessage = savedMessage,
                    onSave = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                Settings.splitTunnelDomains = text
                                Settings.splitTunnelMode = mode
                            }
                            savedMessage = if (serviceStatus == Status.Started) {
                                // Перечитываем конфиг сразу. Раньше показывался только короткий
                                // снекбар с кнопкой «Перезапустить», и без нажатия настройка
                                // молча не применялась — человек считал, что фича не работает.
                                val failure = withContext(Dispatchers.IO) {
                                    try {
                                        Libbox.newStandaloneCommandClient().serviceReload()
                                        null
                                    } catch (e: Exception) {
                                        e
                                    }
                                }
                                if (failure == null) msgApplied else msgManual
                            } else {
                                msgLater
                            }
                        }
                    },
                )
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
