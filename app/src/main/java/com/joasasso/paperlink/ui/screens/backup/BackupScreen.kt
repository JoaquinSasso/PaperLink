package com.joasasso.paperlink.ui.screens.backup

import android.app.Activity
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.backup.RemoteBackupInfo
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activeOperation by viewModel.activeOperation.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val createZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) viewModel.exportZip(uri)
    }

    val openZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importZip(uri)
    }

    val authLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        viewModel.onAuthResult(result.resultCode == Activity.RESULT_OK, result.data)
    }

    LaunchedEffect(events.authResolution) {
        val pendingIntent = events.authResolution ?: return@LaunchedEffect
        viewModel.onAuthResolutionLaunched()
        authLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
    }

    val messageText = when (val msg = events.message) {
        is BackupViewModel.UiMessage.Res -> stringResource(msg.resId)
        is BackupViewModel.UiMessage.Restored -> stringResource(
            R.string.backup_success_restore_counts,
            msg.addedLinks,
            msg.addedAttachments,
        )
        null -> null
    }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.onMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val now = System.currentTimeMillis()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (settings.driveNeedsReauth) {
                WarningCard(
                    text = stringResource(R.string.home_backup_reauth_warning),
                    actionLabel = stringResource(R.string.backup_drive_reauthorize),
                    onAction = viewModel::backupToDriveNow,
                )
            } else if (settings.isStale(now)) {
                WarningCard(
                    text = stringResource(R.string.home_backup_stale_warning),
                    actionLabel = stringResource(R.string.backup_drive_now),
                    onAction = viewModel::backupToDriveNow,
                )
            }

            // Sección Google Drive
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.backup_drive_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.backup_drive_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val lastBackupAt = settings.lastBackupAt
                    val lastSummary = if (lastBackupAt != null) {
                        val dateText = DateUtils.getRelativeTimeSpanString(
                            lastBackupAt,
                            now,
                            DateUtils.MINUTE_IN_MILLIS,
                            DateUtils.FORMAT_ABBREV_RELATIVE,
                        ).toString()
                        val sizeText = Formatter.formatShortFileSize(context, settings.lastBackupSizeBytes ?: 0L)
                        stringResource(R.string.backup_drive_last_summary, dateText, sizeText)
                    } else {
                        stringResource(R.string.backup_drive_never)
                    }
                    Text(
                        text = lastSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.backup_drive_auto_switch),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                        )
                        Switch(
                            checked = settings.driveEnabled,
                            onCheckedChange = viewModel::onToggleAutoBackup,
                        )
                    }

                    if (settings.driveEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.backup_drive_charging_switch),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f).padding(end = 12.dp),
                            )
                            Switch(
                                checked = settings.chargingOnly,
                                onCheckedChange = viewModel::onToggleChargingOnly,
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = viewModel::backupToDriveNow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.backup_drive_now))
                    }
                    OutlinedButton(
                        onClick = viewModel::restoreFromDriveNow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.backup_drive_restore))
                    }
                }
            }

            // Sección ZIP manual
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.backup_zip_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.backup_zip_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = {
                                val fileName = "paperlink-backup-${LocalDate.now()}.zip"
                                createZipLauncher.launch(fileName)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.backup_zip_export))
                        }
                        OutlinedButton(
                            onClick = {
                                openZipLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.backup_zip_import))
                        }
                    }
                }
            }
        }
    }

    events.foundRemoteBackup?.let { info ->
        RemoteBackupFoundDialog(
            info = info,
            onRestore = viewModel::confirmRestoreRemoteBackup,
            onDismiss = viewModel::dismissRemoteBackupPrompt,
        )
    }

    activeOperation?.let { op ->
        BackupProgressDialog(
            operation = op,
            onCancel = viewModel::cancelOperation,
        )
    }
}

@Composable
private fun WarningCard(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            TextButton(
                onClick = onAction,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(actionLabel, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun RemoteBackupFoundDialog(
    info: RemoteBackupInfo,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val dateText = DateUtils.getRelativeTimeSpanString(
        info.createdAt,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
    val sizeText = Formatter.formatShortFileSize(context, info.totalSizeBytes)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_found_dialog_title)) },
        text = {
            Text(
                stringResource(
                    R.string.backup_found_dialog_message,
                    dateText,
                    info.linkCount,
                    sizeText,
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onRestore) {
                Text(stringResource(R.string.backup_found_dialog_restore))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.backup_found_dialog_skip))
            }
        },
    )
}

@Composable
private fun BackupProgressDialog(
    operation: BackupViewModel.ActiveOperation,
    onCancel: () -> Unit,
) {
    val progress = operation.progress
    AlertDialog(
        onDismissRequest = { /* Solo se cierra terminando o tocando Cancelar */ },
        title = { Text(stringResource(operation.kind.titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (progress != null && progress.totalFiles > 0) {
                    Text(
                        stringResource(
                            R.string.import_progress_file,
                            progress.currentFiles.coerceAtMost(progress.totalFiles),
                            progress.totalFiles,
                        )
                    )
                    LinearProgressIndicator(
                        progress = {
                            (progress.currentFiles.toFloat() / progress.totalFiles.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
