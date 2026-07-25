package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoConnectScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.auto_connect_title)) },
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
    var onOpen by remember { mutableStateOf(Settings.autoConnectOnAppOpen) }
    var onBoot by remember { mutableStateOf(Settings.autoStartOnBoot) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    stringResource(R.string.auto_connect_on_open_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            supportingContent = { Text(stringResource(R.string.auto_connect_on_open_summary)) },
            trailingContent = {
                Switch(
                    checked = onOpen,
                    onCheckedChange = { checked ->
                        onOpen = checked
                        scope.launch(Dispatchers.IO) { Settings.autoConnectOnAppOpen = checked }
                    },
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )

        ListItem(
            headlineContent = {
                Text(
                    stringResource(R.string.auto_start_boot_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            supportingContent = { Text(stringResource(R.string.auto_start_boot_summary)) },
            trailingContent = {
                Switch(
                    checked = onBoot,
                    onCheckedChange = { checked ->
                        onBoot = checked
                        scope.launch(Dispatchers.IO) { Settings.autoStartOnBoot = checked }
                    },
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
