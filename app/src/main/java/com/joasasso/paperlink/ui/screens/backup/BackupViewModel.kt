package com.joasasso.paperlink.ui.screens.backup

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.backup.BackupException
import com.joasasso.paperlink.data.backup.BackupProgress
import com.joasasso.paperlink.data.backup.DriveAuthManager
import com.joasasso.paperlink.data.backup.DriveBackupManager
import com.joasasso.paperlink.data.backup.MergeCounts
import com.joasasso.paperlink.data.backup.RemoteBackupInfo
import com.joasasso.paperlink.data.backup.ZipBackupManager
import com.joasasso.paperlink.data.preferences.BackupSettings
import com.joasasso.paperlink.data.preferences.UserPreferencesRepository
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.ui.common.messageRes
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Pantalla de respaldo: exportación/importación ZIP manual y respaldo incremental en Google Drive.
 * La acción de Drive que espera autorización (`KEY_PENDING_DRIVE_ACTION`) se guarda en
 * [SavedStateHandle] para sobrevivir a la recreación de la actividad o muerte del proceso
 * mientras está abierta la pantalla de consentimiento de Google.
 */
class BackupViewModel(
    private val handle: SavedStateHandle,
    private val repository: LinkRepository,
    private val preferences: UserPreferencesRepository,
    private val zipBackupManager: ZipBackupManager,
    private val driveAuthManager: DriveAuthManager,
    private val driveBackupManager: DriveBackupManager,
) : ViewModel() {

    enum class OperationKind(@param:StringRes val titleRes: Int) {
        EXPORT_ZIP(R.string.backup_progress_exporting),
        IMPORT_ZIP(R.string.backup_progress_importing),
        DRIVE_CHECKING(R.string.backup_progress_checking),
        DRIVE_BACKUP(R.string.backup_progress_uploading),
        DRIVE_RESTORE(R.string.backup_progress_restoring),
    }

    private enum class PendingDriveAction {
        CONNECT,
        BACKUP_NOW,
        RESTORE,
    }

    data class ActiveOperation(
        val kind: OperationKind,
        val progress: BackupProgress? = null,
    )

    sealed interface UiMessage {
        data class Res(@param:StringRes val resId: Int) : UiMessage
        data class Restored(val addedLinks: Int, val addedAttachments: Int) : UiMessage
    }

    data class Events(
        val authResolution: PendingIntent? = null,
        val foundRemoteBackup: RemoteBackupInfo? = null,
        val message: UiMessage? = null,
    )

    val settings: StateFlow<BackupSettings> = preferences.backupSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupSettings())

    private val _activeOperation = MutableStateFlow<ActiveOperation?>(null)
    val activeOperation: StateFlow<ActiveOperation?> = _activeOperation.asStateFlow()

    private val _events = MutableStateFlow(Events())
    val events: StateFlow<Events> = _events.asStateFlow()

    private var operationJob: Job? = null

    fun exportZip(destinationUri: Uri) {
        runOperation(OperationKind.EXPORT_ZIP) { updateProgress ->
            zipBackupManager.exportZip(destinationUri, updateProgress)
            _events.update { it.copy(message = UiMessage.Res(R.string.backup_success_export)) }
        }
    }

    fun importZip(sourceUri: Uri) {
        runOperation(OperationKind.IMPORT_ZIP) { updateProgress ->
            val counts = zipBackupManager.importZip(sourceUri, updateProgress)
            emitRestoreMessage(counts)
        }
    }

    fun onToggleAutoBackup(enabled: Boolean) {
        if (!enabled) {
            viewModelScope.launch { driveBackupManager.disableDriveBackup() }
            return
        }
        startConnectDrive()
    }

    fun onToggleChargingOnly(chargingOnly: Boolean) {
        viewModelScope.launch {
            driveBackupManager.setChargingOnly(chargingOnly)
        }
    }

    fun backupToDriveNow() {
        handle[KEY_PENDING_DRIVE_ACTION] = PendingDriveAction.BACKUP_NOW.name
        runOperation(OperationKind.DRIVE_BACKUP) { updateProgress ->
            driveBackupManager.backupNow(updateProgress)
            handle[KEY_PENDING_DRIVE_ACTION] = null
            _events.update { it.copy(message = UiMessage.Res(R.string.backup_success_drive)) }
        }
    }

    fun restoreFromDriveNow() {
        handle[KEY_PENDING_DRIVE_ACTION] = PendingDriveAction.RESTORE.name
        runOperation(OperationKind.DRIVE_RESTORE) { updateProgress ->
            val counts = driveBackupManager.restoreFromDrive(updateProgress)
            handle[KEY_PENDING_DRIVE_ACTION] = null
            emitRestoreMessage(counts)
        }
    }

    fun onAuthResult(resultOk: Boolean, data: Intent?) {
        val pendingName = handle.get<String>(KEY_PENDING_DRIVE_ACTION)
        handle[KEY_PENDING_DRIVE_ACTION] = null
        if (!resultOk) return

        val action = pendingName?.let {
            runCatching { PendingDriveAction.valueOf(it) }.getOrNull()
        } ?: PendingDriveAction.CONNECT

        try {
            val token = driveAuthManager.getTokenFromIntent(data)
            when (action) {
                PendingDriveAction.CONNECT -> completeConnectDrive(token)
                PendingDriveAction.BACKUP_NOW -> backupToDriveNow()
                PendingDriveAction.RESTORE -> restoreFromDriveNow()
            }
        } catch (e: Exception) {
            log("Failed to process Drive authorization result", e)
            _events.update { it.copy(message = UiMessage.Res(e.messageRes())) }
        }
    }

    fun confirmRestoreRemoteBackup() {
        _events.update { it.copy(foundRemoteBackup = null) }
        restoreFromDriveNow()
    }

    fun dismissRemoteBackupPrompt() {
        _events.update { it.copy(foundRemoteBackup = null) }
        backupToDriveNow()
    }

    fun cancelOperation() {
        operationJob?.cancel()
    }

    fun onAuthResolutionLaunched() = _events.update { it.copy(authResolution = null) }
    fun onMessageShown() = _events.update { it.copy(message = null) }

    private fun startConnectDrive() {
        handle[KEY_PENDING_DRIVE_ACTION] = PendingDriveAction.CONNECT.name
        runOperation(OperationKind.DRIVE_CHECKING) {
            driveBackupManager.enableDriveBackup()
            val localLinks = repository.observeCount().first()
            val remoteBackup = if (localLinks == 0) {
                driveBackupManager.checkRemoteBackup()
            } else {
                null
            }
            handle[KEY_PENDING_DRIVE_ACTION] = null
            if (remoteBackup != null && remoteBackup.linkCount > 0) {
                _events.update { it.copy(foundRemoteBackup = remoteBackup) }
            } else {
                _activeOperation.value = ActiveOperation(OperationKind.DRIVE_BACKUP)
                driveBackupManager.backupNow { progress ->
                    _activeOperation.value = ActiveOperation(OperationKind.DRIVE_BACKUP, progress)
                }
                _events.update { it.copy(message = UiMessage.Res(R.string.backup_success_drive)) }
            }
        }
    }

    private fun completeConnectDrive(token: String) {
        runOperation(OperationKind.DRIVE_CHECKING) {
            driveBackupManager.enableDriveBackup(token)
            val localLinks = repository.observeCount().first()
            val remoteBackup = if (localLinks == 0) {
                driveBackupManager.checkRemoteBackup()
            } else {
                null
            }
            if (remoteBackup != null && remoteBackup.linkCount > 0) {
                _events.update { it.copy(foundRemoteBackup = remoteBackup) }
            } else {
                _activeOperation.value = ActiveOperation(OperationKind.DRIVE_BACKUP)
                driveBackupManager.backupNow { progress ->
                    _activeOperation.value = ActiveOperation(OperationKind.DRIVE_BACKUP, progress)
                }
                _events.update { it.copy(message = UiMessage.Res(R.string.backup_success_drive)) }
            }
        }
    }

    private fun emitRestoreMessage(counts: MergeCounts) {
        val msg = if (counts.addedLinks == 0 && counts.addedAttachments == 0) {
            UiMessage.Res(R.string.backup_success_restore_up_to_date)
        } else {
            UiMessage.Restored(counts.addedLinks, counts.addedAttachments)
        }
        _events.update { it.copy(message = msg) }
    }

    private fun runOperation(
        kind: OperationKind,
        block: suspend (onProgress: (BackupProgress) -> Unit) -> Unit,
    ) {
        if (operationJob?.isActive == true) return
        _activeOperation.value = ActiveOperation(kind, progress = null)
        operationJob = viewModelScope.launch {
            try {
                block { progress ->
                    _activeOperation.value = ActiveOperation(kind, progress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException.AuthRequired) {
                if (e.resolution != null) {
                    _events.update { it.copy(authResolution = e.resolution) }
                } else {
                    log("Drive auth failed without resolution", e)
                    _events.update { it.copy(message = UiMessage.Res(e.messageRes())) }
                }
            } catch (e: Exception) {
                log("Backup operation $kind failed", e)
                _events.update { it.copy(message = UiMessage.Res(e.messageRes())) }
            } finally {
                _activeOperation.value = null
            }
        }
    }

    companion object {
        private const val KEY_PENDING_DRIVE_ACTION = "pending_drive_action"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PaperLinkApp
                BackupViewModel(
                    handle = createSavedStateHandle(),
                    repository = app.container.linkRepository,
                    preferences = app.container.userPreferencesRepository,
                    zipBackupManager = app.container.zipBackupManager,
                    driveAuthManager = app.container.driveAuthManager,
                    driveBackupManager = app.container.driveBackupManager,
                )
            }
        }
    }
}
