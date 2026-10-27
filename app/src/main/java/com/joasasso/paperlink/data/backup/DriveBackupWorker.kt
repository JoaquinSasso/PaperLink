package com.joasasso.paperlink.data.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Tarea de WorkManager que sincroniza en segundo plano con Google Drive cuando hay conexión Wi-Fi
 * (no medida): corre al agregar o modificar contenido, al abrir la app con Wi-Fi, al conectarse a
 * una red Wi-Fi si había quedado pendiente, y además una vez por día como respaldo periódico.
 */
class DriveBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as PaperLinkApp).container
        val settings = container.userPreferencesRepository.backupSettings.first()
        if (!settings.driveEnabled) return Result.success()

        return try {
            container.batchImporter.awaitIdle()
            container.driveBackupManager.syncNow()
            log("Automatic Drive sync succeeded")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException.AuthRequired) {
            log("Automatic Drive sync requires re-authorization", e)
            Result.failure()
        } catch (e: BackupException.NetworkError) {
            log("Automatic Drive sync network error (attempt $runAttemptCount)", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        } catch (e: Exception) {
            log("Automatic Drive sync failed", e)
            Result.failure()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "paperlink_daily_drive_backup"
        const val UNIQUE_SYNC_WORK_NAME = "paperlink_auto_drive_sync"
        private const val MAX_RETRIES = 3
    }
}

/**
 * Implementación de [BackupScheduler] sobre [WorkManager].
 */
class WorkManagerBackupScheduler(
    private val context: Context,
) : BackupScheduler {

    override fun scheduleDaily(chargingOnly: Boolean) {
        enqueue(chargingOnly, ExistingPeriodicWorkPolicy.UPDATE)
    }

    fun ensureScheduled(chargingOnly: Boolean) {
        enqueue(chargingOnly, ExistingPeriodicWorkPolicy.KEEP)
    }

    override fun scheduleSync(debounce: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val builder = OneTimeWorkRequestBuilder<DriveBackupWorker>()
            .setConstraints(constraints)
        if (debounce) {
            builder.setInitialDelay(SYNC_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            DriveBackupWorker.UNIQUE_SYNC_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            builder.build(),
        )
    }

    private fun enqueue(chargingOnly: Boolean, policy: ExistingPeriodicWorkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(chargingOnly)
            .build()

        // Al activar el respaldo ya se respalda en el momento (DriveSync): el diario arranca mañana.
        val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setInitialDelay(1, TimeUnit.DAYS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DriveBackupWorker.UNIQUE_WORK_NAME,
            policy,
            request,
        )
    }

    override fun cancel() {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(DriveBackupWorker.UNIQUE_WORK_NAME)
        workManager.cancelUniqueWork(DriveBackupWorker.UNIQUE_SYNC_WORK_NAME)
    }

    companion object {
        private const val SYNC_DEBOUNCE_SECONDS = 3L
    }
}
