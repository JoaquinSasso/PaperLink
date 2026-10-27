package com.joasasso.paperlink.data.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.R
import com.joasasso.paperlink.ui.common.messageRes
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Respaldo o restauración con Google Drive pedidos por el usuario, en segundo plano: siguen aunque
 * se salga de la app, con una notificación de progreso (servicio en primer plano `dataSync`).
 * La autorización de Google se resuelve antes de encolar, en la pantalla de Respaldo.
 */
class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val mode = DriveSync.Mode.valueOf(inputData.getString(KEY_MODE) ?: DriveSync.Mode.BACKUP.name)
    private val notifications = NotificationManagerCompat.from(appContext)

    override suspend fun doWork(): Result = coroutineScope {
        val manager = (applicationContext as PaperLinkApp).container.driveBackupManager
        ensureChannel(applicationContext)
        setForeground(foregroundInfo(null))

        // El progreso llega por archivo; se publica el último (conflado) para no saturar.
        val progress = MutableStateFlow<BackupProgress?>(null)
        val publisher = launch {
            progress.filterNotNull().collect { p ->
                setProgress(progressData(p))
                setForeground(foregroundInfo(p))
            }
        }
        try {
            val output = when (mode) {
                DriveSync.Mode.BACKUP -> {
                    val summary = manager.backupNow { progress.value = it }
                    workDataOf(
                        KEY_ADDED_LINKS to (summary.merged?.addedLinks ?: 0),
                        KEY_ADDED_ATTACHMENTS to (summary.merged?.addedAttachments ?: 0),
                    )
                }
                DriveSync.Mode.RESTORE -> {
                    val counts = manager.restoreFromDrive { progress.value = it }
                    workDataOf(
                        KEY_ADDED_LINKS to counts.addedLinks,
                        KEY_ADDED_ATTACHMENTS to counts.addedAttachments,
                    )
                }
            }
            notifyFinished(error = null)
            Result.success(output)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Drive $mode failed", e)
            val message = applicationContext.getString(e.messageRes())
            notifyFinished(error = message)
            Result.failure(workDataOf(KEY_ERROR to message))
        } finally {
            publisher.cancel()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(progress: BackupProgress?): ForegroundInfo {
        val notification = progressNotification(progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID_PROGRESS, notification)
        }
    }

    private fun progressNotification(progress: BackupProgress?) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_backup)
            .setContentTitle(applicationContext.getString(DriveSync.titleRes(mode, progress)))
            .apply {
                if (progress != null && progress.totalFiles > 0) {
                    setContentText(
                        applicationContext.getString(R.string.import_progress_file, progress.currentFiles, progress.totalFiles)
                    )
                    setProgress(progress.totalFiles, progress.currentFiles, false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                0,
                applicationContext.getString(R.string.action_cancel),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()

    /** Aviso final (la notificación de progreso desaparece con el trabajo). */
    private fun notifyFinished(error: String?) {
        val title = when {
            error != null && mode == DriveSync.Mode.BACKUP -> R.string.drive_sync_backup_failed
            error != null -> R.string.drive_sync_restore_failed
            mode == DriveSync.Mode.BACKUP -> R.string.backup_success_drive
            else -> R.string.drive_sync_restore_done
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_backup)
            .setContentTitle(applicationContext.getString(title))
            .apply { if (error != null) setContentText(error) }
            .setAutoCancel(true)
            .build()
        notify(NOTIFICATION_ID_RESULT, notification)
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        // Sin permiso el trabajo sigue igual; solo no se ve la notificación.
        if (granted) notifications.notify(id, notification)
    }

    companion object {
        const val KEY_MODE = "mode"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val KEY_MERGING = "merging"
        const val KEY_ADDED_LINKS = "added_links"
        const val KEY_ADDED_ATTACHMENTS = "added_attachments"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "drive_backup"
        private const val NOTIFICATION_ID_PROGRESS = 41
        private const val NOTIFICATION_ID_RESULT = 42

        private fun progressData(p: BackupProgress) = workDataOf(
            KEY_CURRENT to p.currentFiles,
            KEY_TOTAL to p.totalFiles,
            KEY_MERGING to p.merging,
        )

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.drive_sync_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

/**
 * Lanza y observa el respaldo o la restauración manual con Drive ([DriveSyncWorker]).
 * Nunca corren dos a la vez (trabajo único); además [DriveBackupManager] serializa con el diario.
 */
class DriveSync(private val context: Context) {

    enum class Mode { BACKUP, RESTORE }

    /** Estado de la última ejecución, para la pantalla de Respaldo. */
    sealed interface Run {
        val id: UUID
        val mode: Mode

        /** Encolado, esperando conexión. */
        data class Waiting(override val id: UUID, override val mode: Mode) : Run
        data class Running(override val id: UUID, override val mode: Mode, val progress: BackupProgress?) : Run
        data class Succeeded(override val id: UUID, override val mode: Mode, val addedLinks: Int, val addedAttachments: Int) : Run
        data class Failed(override val id: UUID, override val mode: Mode, val message: String?) : Run
        data class Cancelled(override val id: UUID, override val mode: Mode) : Run
    }

    private val workManager get() = WorkManager.getInstance(context)

    val lastRun: Flow<Run?> = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
        .map { infos -> infos.lastOrNull()?.toRun() }

    /** Encola la operación; si ya hay una en curso, la deja seguir y no encola otra. */
    fun start(mode: Mode): UUID {
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setInputData(workDataOf(DriveSyncWorker.KEY_MODE to mode.name))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(mode.name)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    private fun WorkInfo.toRun(): Run {
        val mode = if (Mode.RESTORE.name in tags) Mode.RESTORE else Mode.BACKUP
        return when (state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> Run.Waiting(id, mode)
            WorkInfo.State.RUNNING -> Run.Running(id, mode, progress.toBackupProgress())
            WorkInfo.State.SUCCEEDED -> Run.Succeeded(
                id,
                mode,
                outputData.getInt(DriveSyncWorker.KEY_ADDED_LINKS, 0),
                outputData.getInt(DriveSyncWorker.KEY_ADDED_ATTACHMENTS, 0),
            )
            WorkInfo.State.FAILED -> Run.Failed(id, mode, outputData.getString(DriveSyncWorker.KEY_ERROR))
            WorkInfo.State.CANCELLED -> Run.Cancelled(id, mode)
        }
    }

    private fun Data.toBackupProgress(): BackupProgress? {
        if (!keyValueMap.containsKey(DriveSyncWorker.KEY_TOTAL)) return null
        return BackupProgress(
            currentFiles = getInt(DriveSyncWorker.KEY_CURRENT, 0),
            totalFiles = getInt(DriveSyncWorker.KEY_TOTAL, 0),
            merging = getBoolean(DriveSyncWorker.KEY_MERGING, false),
        )
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "paperlink_drive_sync_now"

        /** Título del progreso según la operación y la etapa. */
        fun titleRes(mode: Mode, progress: BackupProgress?): Int = when {
            mode == Mode.RESTORE -> R.string.backup_progress_restoring
            progress?.merging == true -> R.string.drive_sync_merging
            else -> R.string.backup_progress_uploading
        }
    }
}
