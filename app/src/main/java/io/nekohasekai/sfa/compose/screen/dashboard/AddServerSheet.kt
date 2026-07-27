package io.nekohasekai.sfa.compose.screen.dashboard

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.base.GlobalEventBus
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.component.qr.QRScanSheet
import io.nekohasekai.sfa.compose.navigation.NewProfileArgs
import io.nekohasekai.sfa.compose.screen.configuration.ProfileImportHandler
import io.nekohasekai.sfa.compose.screen.qrscan.QRScanResult
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.ktx.friendlyImportError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Универсальная точка входа «Добавить сервер»: понятные СПОСОБЫ ВВОДА, а не технические типы.
 * Что именно вставлено — подписка, одиночный сервер или sing-box JSON — определяет importFromText
 * сам, пользователю выбирать не нужно.
 *
 * Компонент нужно размещать так, чтобы он был в композиции ВСЕГДА (не внутри `if (visible)`):
 * файловый launcher и состояние QR/подтверждения должны переживать закрытие самого листа
 * (например, файл выбирается уже после того, как лист скрылся). Видимостью управляет параметр
 * [visible] — под ним скрыт только сам ModalBottomSheet.
 *
 * @param onOpenManual открыть ручной конструктор профиля (или форму удалённого профиля из QR).
 * @param onImported профиль импортирован — открыть его редактор.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpenManual: (NewProfileArgs) -> Unit,
    onImported: (Profile) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val importHandler = remember { ProfileImportHandler(context) }

    var showLinkDialog by remember { mutableStateOf(false) }
    var linkText by remember { mutableStateOf("") }
    var showQRScanSheet by remember { mutableStateOf(false) }
    var showImportConfirmDialog by remember { mutableStateOf(false) }
    var pendingImportName by remember { mutableStateOf<String?>(null) }
    var pendingQrsData by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    // Импорт из произвольного текста — из буфера или из поля «по ссылке».
    val importText: (String) -> Unit = { text ->
        coroutineScope.launch {
            when (val result = importHandler.importFromText(text)) {
                is ProfileImportHandler.ImportResult.Success ->
                    withContext(Dispatchers.Main) { onImported(result.profile) }
                is ProfileImportHandler.ImportResult.Error ->
                    withContext(Dispatchers.Main) {
                        GlobalEventBus.tryEmit(UiEvent.ErrorMessage(context.friendlyImportError(result.message)))
                    }
            }
        }
    }

    val importFromFileLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent(),
        ) { uri ->
            uri?.let {
                coroutineScope.launch {
                    when (val parseResult = importHandler.parseUri(uri)) {
                        is ProfileImportHandler.UriParseResult.Success -> {
                            withContext(Dispatchers.Main) {
                                pendingImportName = parseResult.name
                                pendingImportUri = uri
                                showImportConfirmDialog = true
                            }
                        }
                        is ProfileImportHandler.UriParseResult.Error -> {
                            withContext(Dispatchers.Main) {
                                GlobalEventBus.tryEmit(UiEvent.ErrorMessage(context.friendlyImportError(parseResult.message)))
                            }
                        }
                    }
                }
            }
        }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = stringResource(R.string.add_server_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
                Text(
                    text = stringResource(R.string.add_server_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
                )

                // Порядок по частоте: из буфера и по ссылке — самые ходовые, поэтому вверху.
                ListItem(
                    modifier = Modifier.clickable {
                        onDismiss()
                        val text = clipboardManager.getText()?.text.orEmpty()
                        if (text.isBlank()) {
                            GlobalEventBus.tryEmit(
                                UiEvent.ErrorMessage(context.getString(R.string.add_server_clipboard_empty)),
                            )
                        } else {
                            importText(text)
                        }
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.ContentPaste, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    headlineContent = { Text(stringResource(R.string.add_server_paste)) },
                    supportingContent = { Text(stringResource(R.string.add_server_paste_desc)) },
                )

                ListItem(
                    modifier = Modifier.clickable {
                        linkText = ""
                        showLinkDialog = true
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.Link, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    headlineContent = { Text(stringResource(R.string.add_server_link)) },
                    supportingContent = { Text(stringResource(R.string.add_server_link_desc)) },
                )

                ListItem(
                    modifier = Modifier.clickable {
                        onDismiss()
                        showQRScanSheet = true
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.QrCodeScanner, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    headlineContent = { Text(stringResource(R.string.add_server_qr)) },
                    supportingContent = { Text(stringResource(R.string.add_server_qr_desc)) },
                )

                ListItem(
                    modifier = Modifier.clickable {
                        onDismiss()
                        importFromFileLauncher.launch("*/*")
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.FileUpload, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    headlineContent = { Text(stringResource(R.string.add_server_file)) },
                    supportingContent = { Text(stringResource(R.string.add_server_file_desc)) },
                )

                ListItem(
                    modifier = Modifier.clickable {
                        onDismiss()
                        onOpenManual(NewProfileArgs())
                    },
                    leadingContent = {
                        Icon(Icons.Outlined.CreateNewFolder, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    headlineContent = { Text(stringResource(R.string.add_server_manual)) },
                    supportingContent = { Text(stringResource(R.string.add_server_manual_desc)) },
                )
            }
        }
    }

    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text(stringResource(R.string.add_server_link)) },
            text = {
                OutlinedTextField(
                    value = linkText,
                    onValueChange = { linkText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.add_server_link_hint)) },
                    placeholder = { Text("https://…   vless://…") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val t = linkText.trim()
                        showLinkDialog = false
                        onDismiss()
                        if (t.isNotEmpty()) importText(t)
                    },
                ) { Text(stringResource(R.string.import_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showLinkDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showImportConfirmDialog && pendingImportName != null) {
        AlertDialog(
            onDismissRequest = {
                showImportConfirmDialog = false
                pendingImportName = null
                pendingQrsData = null
                pendingImportUri = null
            },
            title = { Text(stringResource(R.string.import_profile_confirm_title)) },
            text = { Text(stringResource(R.string.import_profile_confirm_message, pendingImportName!!)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        val qrsData = pendingQrsData
                        val importUri = pendingImportUri
                        pendingImportName = null
                        pendingQrsData = null
                        pendingImportUri = null
                        coroutineScope.launch {
                            if (qrsData != null) {
                                when (val result = importHandler.importFromQRSData(qrsData)) {
                                    is ProfileImportHandler.ImportResult.Success ->
                                        withContext(Dispatchers.Main) { onImported(result.profile) }
                                    is ProfileImportHandler.ImportResult.Error ->
                                        withContext(Dispatchers.Main) {
                                            GlobalEventBus.tryEmit(UiEvent.ErrorMessage(context.friendlyImportError(result.message)))
                                        }
                                }
                            } else if (importUri != null) {
                                when (val result = importHandler.importFromUri(importUri)) {
                                    is ProfileImportHandler.ImportResult.Success ->
                                        withContext(Dispatchers.Main) { onImported(result.profile) }
                                    is ProfileImportHandler.ImportResult.Error ->
                                        withContext(Dispatchers.Main) {
                                            GlobalEventBus.tryEmit(UiEvent.ErrorMessage(context.friendlyImportError(result.message)))
                                        }
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.import_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        pendingImportName = null
                        pendingQrsData = null
                        pendingImportUri = null
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showQRScanSheet) {
        QRScanSheet(
            onDismiss = { showQRScanSheet = false },
            onScanResult = { result ->
                showQRScanSheet = false
                when (result) {
                    is QRScanResult.QRSData -> {
                        coroutineScope.launch {
                            when (val parseResult = importHandler.parseQRSData(result.data)) {
                                is ProfileImportHandler.QRSParseResult.Success -> {
                                    withContext(Dispatchers.Main) {
                                        pendingImportName = parseResult.name
                                        pendingQrsData = result.data
                                        showImportConfirmDialog = true
                                    }
                                }
                                is ProfileImportHandler.QRSParseResult.Error -> {
                                    withContext(Dispatchers.Main) {
                                        GlobalEventBus.tryEmit(UiEvent.ErrorMessage(context.friendlyImportError(parseResult.message)))
                                    }
                                }
                            }
                        }
                    }
                    is QRScanResult.RemoteProfile -> {
                        coroutineScope.launch {
                            when (val parseResult = importHandler.parseQRCode(result.uri.toString())) {
                                is ProfileImportHandler.QRCodeParseResult.RemoteProfile -> {
                                    withContext(Dispatchers.Main) {
                                        onOpenManual(
                                            NewProfileArgs(
                                                importName = parseResult.name,
                                                importUrl = parseResult.url,
                                            ),
                                        )
                                    }
                                }
                                is ProfileImportHandler.QRCodeParseResult.LocalProfile -> {
                                    when (val importResult = importHandler.importFromQRCode(result.uri.toString())) {
                                        is ProfileImportHandler.ImportResult.Success ->
                                            withContext(Dispatchers.Main) { onImported(importResult.profile) }
                                        is ProfileImportHandler.ImportResult.Error ->
                                            withContext(Dispatchers.Main) {
                                                GlobalEventBus.tryEmit(UiEvent.ErrorMessage(context.friendlyImportError(importResult.message)))
                                            }
                                    }
                                }
                                is ProfileImportHandler.QRCodeParseResult.Error -> {
                                    withContext(Dispatchers.Main) {
                                        GlobalEventBus.tryEmit(UiEvent.ErrorMessage(context.friendlyImportError(parseResult.message)))
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}
