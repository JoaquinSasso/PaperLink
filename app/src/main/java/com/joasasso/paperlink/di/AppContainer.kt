package com.joasasso.paperlink.di

import android.content.Context
import com.joasasso.paperlink.data.backup.DriveAppDataClient
import com.joasasso.paperlink.data.backup.DriveAuthManager
import com.joasasso.paperlink.data.backup.DriveBackupManager
import com.joasasso.paperlink.data.backup.WorkManagerBackupScheduler
import com.joasasso.paperlink.data.backup.ZipBackupManager
import com.joasasso.paperlink.data.files.AttachmentStore
import com.joasasso.paperlink.data.local.PaperLinkDatabase
import com.joasasso.paperlink.data.preferences.UserPreferencesRepository
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
        LinkRepository(database.linkDao(), attachmentStore, applicationScope)
    }

    val userPreferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(context)
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
}
