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
import com.joasasso.paperlink.data.backup.DriveSync
import com.joasasso.paperlink.data.backup.MergeCounts
import com.joasasso.paperlink.data.backup.ZipBackupManager
import com.joasasso.paperlink.data.preferences.BackupSettings
import com.joasasso.paperlink.data.preferences.UserPreferencesRepository
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
 * Respaldar y restaurar con Drive corren en segundo plano ([DriveSync]): la pantalla solo pide la
 * autorización de Google, encola el trabajo y muestra su avance.
 * La acción de Drive que espera autorización (`KEY_PENDING_DRIVE_ACTION`) y la ejecución cuyo
 * resultado falta mostrar (`KEY_WATCHED_RUN`) se guardan en [SavedStateHandle] para sobrevivir a
 * la recreación de la actividad o a la muerte del proceso.
 */
class BackupViewModel(
    private val handle: SavedStateHandle,
    private val preferences: UserPreferencesRepository,
    private val zipBackupManager: ZipBackupManager,
    private val driveAuthManager: DriveAuthManager,
    private val driveBackupManager: DriveBackupManager,
    private val driveSync: DriveSync,
) : ViewModel() {

    enum class OperationKind(@param:StringRes val titleRes: Int) {
        EXPORT_ZIP(R.string.backup_progress_exporting),
        IMPORT_ZIP(R.string.backup_progress_importing),
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
        data class BackedUpWithMerge(val addedLinks: Int, val addedAttachments: Int) : UiMessage
        data class Text(val text: String) : UiMessage
    }

    data class Events(
        val authResolution: PendingIntent? = null,
        val message: UiMessage? = null,
    )

    val settings: StateFlow<BackupSettings> = preferences.backupSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupSettings())

    private val _activeOperation = MutableStateFlow<ActiveOperation?>(null)
    val activeOperation: StateFlow<ActiveOperation?> = _activeOperation.asStateFlow()

    private val _events = MutableStateFlow(Events())
    val events: StateFlow<Events> = _events.asStateFlow()

    /** Respaldo o restauración con Drive en curso (o el último). */
    val driveRun: StateFlow<DriveSync.Run?> = driveSync.lastRun
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var operationJob: Job? = null

    init {
        // El resultado se muestra solo para la ejecución que se lanzó desde esta pantalla.
        viewModelScope.launch {
            driveSync.lastRun.collect { run ->
                if (run == null || run.id.toString() != handle.get<String>(KEY_WATCHED_RUN)) return@collect
                val message = when (run) {
                    is DriveSync.Run.Waiting, is DriveSync.Run.Running -> return@collect
                    is DriveSync.Run.Succeeded -> when {
                        run.mode == DriveSync.Mode.RESTORE -> restoreMessage(run.addedLinks, run.addedAttachments)
                        run.addedLinks > 0 || run.addedAttachments > 0 ->
                            UiMessage.BackedUpWithMerge(run.addedLinks, run.addedAttachments)
                        else -> UiMessage.Res(R.string.backup_success_drive)
                    }
                    is DriveSync.Run.Failed -> run.message?.let { UiMessage.Text(it) } ?: UiMessage.Res(R.string.error_generic)
                    is DriveSync.Run.Cancelled -> null
                }
                handle[KEY_WATCHED_RUN] = null
                if (message != null) _events.update { it.copy(message = message) }
            }
        }
    }

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
        startDrive(PendingDriveAction.CONNECT)
    }

    fun onToggleChargingOnly(chargingOnly: Boolean) {
        viewModelScope.launch {
            driveBackupManager.setChargingOnly(chargingOnly)
        }
    }

    /** Respalda en Drive; si Drive tiene un respaldo que este teléfono no tiene, primero lo une. */
    fun backupToDriveNow() = startDrive(PendingDriveAction.BACKUP_NOW)

    fun restoreFromDriveNow() = startDrive(PendingDriveAction.RESTORE)

    fun cancelDriveRun() = driveSync.cancel()

    fun onAuthResult(resultOk: Boolean, data: Intent?) {
        val pendingName = handle.get<String>(KEY_PENDING_DRIVE_ACTION)
        log("Drive auth result received: resultOk=$resultOk, pending=$pendingName")
        handle[KEY_PENDING_DRIVE_ACTION] = null
        if (!resultOk) return

        val action = pendingName?.let {
            runCatching { PendingDriveAction.valueOf(it) }.getOrNull()
        } ?: PendingDriveAction.CONNECT

        try {
            // Guarda el token en el administrador de autorización para las llamadas siguientes.
            driveAuthManager.getTokenFromIntent(data)
            startDrive(action)
        } catch (e: Exception) {
            log("Failed to process Drive authorization result", e)
            _events.update { it.copy(message = UiMessage.Res(e.messageRes())) }
        }
    }

    fun cancelOperation() {
        operationJob?.cancel()
    }

    fun onAuthResolutionLaunched() = _events.update { it.copy(authResolution = null) }
    fun onMessageShown() = _events.update { it.copy(message = null) }

    /**
     * Pide la autorización de Google si hace falta (acá, con la pantalla abierta) y encola el
     * trabajo en segundo plano. Activar el respaldo automático también respalda en el momento.
     */
    private fun startDrive(action: PendingDriveAction) {
        val current = driveRun.value
        if (current is DriveSync.Run.Waiting || current is DriveSync.Run.Running) return
        handle[KEY_PENDING_DRIVE_ACTION] = action.name
        viewModelScope.launch {
            try {
                driveBackupManager.ensureAuthorized()
                handle[KEY_PENDING_DRIVE_ACTION] = null
                if (action == PendingDriveAction.CONNECT) driveBackupManager.enableDriveBackup()
                val mode = if (action == PendingDriveAction.RESTORE) DriveSync.Mode.RESTORE else DriveSync.Mode.BACKUP
                handle[KEY_WATCHED_RUN] = driveSync.start(mode).toString()
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
                log("Could not start Drive $action", e)
                handle[KEY_PENDING_DRIVE_ACTION] = null
                _events.update { it.copy(message = UiMessage.Res(e.messageRes())) }
            }
        }
    }

    private fun emitRestoreMessage(counts: MergeCounts) {
        _events.update { it.copy(message = restoreMessage(counts.addedLinks, counts.addedAttachments)) }
    }

    private fun restoreMessage(addedLinks: Int, addedAttachments: Int): UiMessage =
        if (addedLinks == 0 && addedAttachments == 0) {
            UiMessage.Res(R.string.backup_success_restore_up_to_date)
        } else {
            UiMessage.Restored(addedLinks, addedAttachments)
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
        private const val KEY_WATCHED_RUN = "watched_drive_run"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PaperLinkApp
                BackupViewModel(
                    handle = createSavedStateHandle(),
                    preferences = app.container.userPreferencesRepository,
                    zipBackupManager = app.container.zipBackupManager,
                    driveAuthManager = app.container.driveAuthManager,
                    driveBackupManager = app.container.driveBackupManager,
                    driveSync = app.container.driveSync,
                )
            }
        }
    }
}
