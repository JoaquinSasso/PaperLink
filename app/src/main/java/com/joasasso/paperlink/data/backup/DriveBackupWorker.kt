package com.joasasso.paperlink.data.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Tarea periódica de WorkManager que ejecuta el respaldo incremental en Google Drive
 * una vez por día con conexión Wi-Fi (no medida) y, opcionalmente, solo cargando.
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
            container.driveBackupManager.backupNow()
            log("Periodic Drive backup succeeded")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException.AuthRequired) {
            log("Periodic Drive backup requires re-authorization", e)
            Result.failure()
        } catch (e: BackupException.RemoteNotMerged) {
            // Drive tiene un respaldo sin fusionar: no se respalda (no se pisa). La Home lo avisa.
            log("Periodic Drive backup skipped: remote backup not merged", e)
            Result.failure()
        } catch (e: BackupException.NetworkError) {
            log("Periodic Drive backup network error (attempt $runAttemptCount)", e)
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        } catch (e: Exception) {
            log("Periodic Drive backup failed", e)
            Result.failure()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "paperlink_daily_drive_backup"
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

    private fun enqueue(chargingOnly: Boolean, policy: ExistingPeriodicWorkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(chargingOnly)
            .build()

        val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DriveBackupWorker.UNIQUE_WORK_NAME,
            policy,
            request,
        )
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(DriveBackupWorker.UNIQUE_WORK_NAME)
    }
}
