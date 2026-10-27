package com.joasasso.paperlink.di

import android.app.ActivityManager
import android.content.Context
import com.joasasso.paperlink.data.backup.DriveAppDataClient
import com.joasasso.paperlink.data.backup.DriveAuthManager
import com.joasasso.paperlink.data.backup.DriveBackupManager
import com.joasasso.paperlink.data.backup.DriveSync
import com.joasasso.paperlink.data.backup.WorkManagerBackupScheduler
import com.joasasso.paperlink.data.backup.ZipBackupManager
import com.joasasso.paperlink.data.files.AttachmentStore
import com.joasasso.paperlink.data.local.PaperLinkDatabase
import com.joasasso.paperlink.data.preferences.UserPreferencesRepository
import com.joasasso.paperlink.data.repository.BatchImporter
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Contenedor de dependencias de la aplicación (DI manual).
 */
class AppContainer(context: Context) {

    /** Trabajo que debe sobrevivir a las pantallas: mantenimiento y purgas diferidas. */
    val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> log("Background task failed", e) }
    )

    private val database: PaperLinkDatabase by lazy { PaperLinkDatabase.build(context) }

    val attachmentStore: AttachmentStore by lazy { AttachmentStore(context) }

    val linkRepository: LinkRepository by lazy {
        LinkRepository(
            dao = database.linkDao(),
            store = attachmentStore,
            externalScope = applicationScope,
            onChanged = { applicationScope.launch { driveBackupManager.onLocalChange() } },
        )
    }

    val userPreferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(context)
    }

    /** Cargas de varios elementos: 3 fotos a la vez (1 en teléfonos con poca RAM). */
    val batchImporter: BatchImporter by lazy {
        val lowRam = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        BatchImporter(linkRepository, attachmentStore, userPreferencesRepository, applicationScope, parallelism = if (lowRam) 1 else 3)
    }

    val zipBackupManager: ZipBackupManager by lazy {
        ZipBackupManager(attachmentStore, linkRepository)
    }

    val driveAuthManager: DriveAuthManager by lazy {
        DriveAuthManager(context)
    }

    val driveBackupManager: DriveBackupManager by lazy {
        DriveBackupManager(
            store = attachmentStore,
            repository = linkRepository,
            preferences = userPreferencesRepository,
            tokenProvider = driveAuthManager,
            remote = DriveAppDataClient(),
            scheduler = WorkManagerBackupScheduler(context),
        )
    }

    /** Respaldo y restauración manuales con Drive, en segundo plano. */
    val driveSync: DriveSync by lazy { DriveSync(context) }
}
