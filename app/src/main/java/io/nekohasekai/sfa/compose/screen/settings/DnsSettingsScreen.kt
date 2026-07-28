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
import io.nekohasekai.sfa.utils.DnsOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsSettingsScreen(navController: NavController, serviceStatus: Status = Status.Stopped) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.dns_settings)) },
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

    var mode by remember { mutableStateOf(Settings.dnsMode) }
    var custom by remember { mutableStateOf(Settings.dnsCustomServer) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    val msgApplied = stringResource(R.string.split_tunnel_saved_applied)
    val msgLater = stringResource(R.string.split_tunnel_saved_later)
    val msgManual = stringResource(R.string.split_tunnel_saved_manual)
    val msgBadIp = stringResource(R.string.dns_custom_invalid)

    // Пары «режим → подпись». Порядок = порядок в списке.
    val options = listOf(
        DnsOverride.MODE_AUTO to stringResource(R.string.dns_mode_auto),
        DnsOverride.MODE_CLOUDFLARE to stringResource(R.string.dns_mode_cloudflare),
        DnsOverride.MODE_GOOGLE to stringResource(R.string.dns_mode_google),
        DnsOverride.MODE_ADGUARD to stringResource(R.string.dns_mode_adguard),
        DnsOverride.MODE_CUSTOM to stringResource(R.string.dns_mode_custom),
    )

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
                    text = stringResource(R.string.dns_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))

                options.forEach { (value, label) ->
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

                if (mode == DnsOverride.MODE_CUSTOM) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = custom,
                        onValueChange = {
                            custom = it
                            savedMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.dns_custom_label)) },
                        placeholder = { Text("94.140.14.14") },
                    )
                }

                SaveBar(
                    savedMessage = savedMessage,
                    onSave = {
                        // Неверный IP в custom не сохраняем: с ним резолв встал бы, а это «нет
                        // интернета». Показываем ошибку и оставляем прежний рабочий DNS.
                        if (mode == DnsOverride.MODE_CUSTOM && !DnsOverride.isIpLiteral(custom)) {
                            savedMessage = msgBadIp
                        } else {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    Settings.dnsMode = mode
                                    Settings.dnsCustomServer = custom.trim()
                                }
                                savedMessage = if (serviceStatus == Status.Started) {
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
                        }
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.dns_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}
