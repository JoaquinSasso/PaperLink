package com.joasasso.paperlink.data.backup

import com.joasasso.paperlink.data.files.AttachmentStore
import com.joasasso.paperlink.data.files.ImportException
import com.joasasso.paperlink.data.preferences.UserPreferencesRepository
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Resumen de un respaldo exitoso en Google Drive. */
data class DriveBackupSummary(
    val timestamp: Long,
    val totalSizeBytes: Long,
    val uploadedFiles: Int,
    val totalLinks: Int,
)

/** Datos del respaldo existente en Google Drive (para ofrecer restaurarlo al iniciar sesión). */
data class RemoteBackupInfo(
    val createdAt: Long,
    val linkCount: Int,
    val attachmentCount: Int,
    val totalSizeBytes: Long,
)

/** Programa o cancela la ejecución periódica del respaldo en segundo plano. */
interface BackupScheduler {
    fun scheduleDaily(chargingOnly: Boolean)
    fun cancel()
}

/**
 * Coordina el respaldo incremental y la restauración con Google Drive (`appDataFolder`).
 *
 * Garantías:
 * - Garantía 12: sube primero los archivos nuevos de `media/` y recién al final sube `manifest.json`.
 *   Si se corta a mitad de camino, el `manifest.json` anterior sigue intacto como último respaldo.
 *   Al restaurar se verifica el `sha256` de cada archivo antes de fusionar en la base.
 * - Garantía 13: registra la fecha del último respaldo exitoso y marca `driveNeedsReauth` si
 *   Google Drive rechaza las credenciales o se revocó el permiso.
 * - Garantía 14: fusiona por `id` de attachment sin pisar códigos ni elementos existentes.
 */
class DriveBackupManager(
    private val store: AttachmentStore,
    private val repository: LinkRepository,
    private val preferences: UserPreferencesRepository,
    private val tokenProvider: DriveTokenProvider,
    private val remote: DriveRemoteStore,
    private val scheduler: BackupScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Activa el respaldo periódico en Google Drive con las preferencias actuales.
     */
    suspend fun enableDriveBackup( token: String? = null) {
        if (token == null) {
            withValidToken { /* verifica que tengamos permiso concedido */ }
        }
        val settings = preferences.backupSettings.first()
        preferences.setDriveBackupEnabled(true)
        scheduler.scheduleDaily(settings.chargingOnly)
    }

    /**
     * Cambia la restricción "Solo cargando" y actualiza la tarea periódica si el respaldo está activo.
     */
    suspend fun setChargingOnly(chargingOnly: Boolean) {
        preferences.setDriveChargingOnly(chargingOnly)
        val settings = preferences.backupSettings.first()
        if (settings.driveEnabled) {
            scheduler.scheduleDaily(chargingOnly)
        }
    }

    /**
     * Desactiva el respaldo automático en Google Drive y cancela la tarea diaria.
     */
    suspend fun disableDriveBackup() {
        preferences.disconnectDrive()
        scheduler.cancel()
    }

    /**
     * Ejecuta un respaldo incremental ahora:
     * 1. Sube solo los archivos de `media/` que todavía no estén en `appDataFolder`.
     * 2. Sube el nuevo `manifest.json` al final.
     * 3. Recién después de confirmar el nuevo `manifest.json`, borra los manifests viejos y archivos
     *    huérfanos en Drive.
     */
    suspend fun backupNow(
        onProgress: (BackupProgress) -> Unit = {},
    ): DriveBackupSummary = withValidToken { token ->
        val manifest = repository.exportSnapshot()
        val remoteFiles = remote.listFiles(token)
        val existingNames = remoteFiles.mapTo(HashSet()) { it.name }

        // Cada archivo en media/<uuid>.<ext> es inmutable; solo se sube si no está ya en Drive.
        val requiredFiles = manifest.attachments
            .filter { it.relativePath != null }
            .distinctBy { it.relativePath!! }
        val toUpload = requiredFiles.filter { it.relativePath!! !in existingNames }

        onProgress(BackupProgress(0, toUpload.size))
        toUpload.forEachIndexed { index, att ->
            currentCoroutineContext().ensureActive()
            val path = att.relativePath!!
            val mimeType = att.mimeType ?: DEFAULT_MIME_TYPE
            store.readFile(path) { input ->
                remote.uploadStream(token, path, mimeType, input)
            }
            onProgress(BackupProgress(index + 1, toUpload.size))
        }

        // El manifest se sube SIEMPRE al final (garantía 12).
        currentCoroutineContext().ensureActive()
        val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        val uploadedManifest = remote.uploadStream(
            accessToken = token,
            name = BackupManifest.MANIFEST_FILE_NAME,
            mimeType = "application/json",
            contentStream = ByteArrayInputStream(manifestBytes),
        )

        // Limpieza posterior al commit del nuevo manifest: si falla no invalida el respaldo ya subido.
        val referencedPaths = requiredFiles.mapTo(HashSet()) { it.relativePath!! }
        for (oldFile in remoteFiles) {
            val isOldManifest = oldFile.name == BackupManifest.MANIFEST_FILE_NAME && oldFile.id != uploadedManifest.id
            val isOrphanMedia = oldFile.name != BackupManifest.MANIFEST_FILE_NAME && oldFile.name !in referencedPaths
            if (isOldManifest || isOrphanMedia) {
                try {
                    remote.deleteFile(token, oldFile.id)
                } catch (e: Exception) {
                    log("Could not delete old Drive file ${oldFile.name}", e)
                }
            }
        }

        val totalSize = manifestBytes.size.toLong() + requiredFiles.sumOf { it.sizeBytes ?: 0L }
        val now = clock()
        preferences.recordDriveBackupSuccess(now, totalSize)
        val settings = preferences.backupSettings.first()
        scheduler.scheduleDaily(settings.chargingOnly)

        DriveBackupSummary(
            timestamp = now,
            totalSizeBytes = totalSize,
            uploadedFiles = toUpload.size,
            totalLinks = manifest.links.size,
        )
    }

    /**
     * Consulta si existe un respaldo válido en `appDataFolder` sin modificar los datos locales.
     */
    suspend fun checkRemoteBackup(): RemoteBackupInfo? = withValidToken { token ->
        val remoteFiles = remote.listFiles(token)
        val manifestFile = remoteFiles.firstOrNull { it.name == BackupManifest.MANIFEST_FILE_NAME }
            ?: return@withValidToken null
        val manifest = downloadManifest(token, manifestFile.id)
        val totalSize = manifest.attachments.sumOf { it.sizeBytes ?: 0L } + (manifestFile.size ?: 0L)
        RemoteBackupInfo(
            createdAt = manifest.createdAt,
            linkCount = manifest.links.size,
            attachmentCount = manifest.attachments.size,
            totalSizeBytes = totalSize,
        )
    }

    /**
     * Descarga los archivos faltantes desde `appDataFolder`, verifica su SHA-256 y fusiona
     * el `manifest.json` con la base local sin pisar nada (garantías 12 y 14).
     */
    suspend fun restoreFromDrive(
        onProgress: (BackupProgress) -> Unit = {},
    ): MergeCounts = withValidToken { token ->
        val remoteFiles = remote.listFiles(token)
        val manifestFile = remoteFiles.firstOrNull { it.name == BackupManifest.MANIFEST_FILE_NAME }
            ?: throw BackupException.NoRemoteBackup()
        val manifest = downloadManifest(token, manifestFile.id)

        val remoteByName = remoteFiles.distinctBy { it.name }.associateBy { it.name }
        val fileAttachments = manifest.attachments
            .filter { it.relativePath != null }
            .distinctBy { it.relativePath!! }

        onProgress(BackupProgress(0, fileAttachments.size))
        val newlyRestoredPaths = mutableListOf<String>()
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            fileAttachments.forEachIndexed { index, att ->
                currentCoroutineContext().ensureActive()
                val path = att.relativePath!!
                if (!store.hasMatchingFile(path, att.sha256)) {
                    val remoteEntry = remoteByName[path]
                        ?: throw BackupException.CorruptBackup("Missing file in Google Drive backup: $path")
                    val created = store.restoreFile(
                        relativePath = path,
                        expectedSha256 = att.sha256,
                        estimatedSize = att.sizeBytes,
                    ) { out ->
                        remote.downloadFile(token, remoteEntry.id) { input ->
                            copyStream(input, out, buffer)
                        }
                    }
                    if (created) newlyRestoredPaths += path
                }
                onProgress(BackupProgress(index + 1, fileAttachments.size))
            }
            repository.commitBackup(manifest, newlyRestoredPaths)
        } catch (e: CancellationException) {
            repository.discardRestoredFiles(newlyRestoredPaths)
            throw e
        } catch (e: BackupException) {
            repository.discardRestoredFiles(newlyRestoredPaths)
            throw e
        } catch (e: ImportException) {
            repository.discardRestoredFiles(newlyRestoredPaths)
            throw e
        } catch (t: Throwable) {
            repository.discardRestoredFiles(newlyRestoredPaths)
            throw t
        }
    }

    private suspend fun downloadManifest(token: String, fileId: String): BackupManifest {
        val text = ByteArrayOutputStream().use { out ->
            val buffer = ByteArray(BUFFER_SIZE)
            remote.downloadFile(token, fileId) { input ->
                var total = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_MANIFEST_BYTES) {
                        throw BackupException.InvalidArchive("Remote manifest exceeds maximum allowed size")
                    }
                    out.write(buffer, 0, read)
                }
            }
            out.toString(Charsets.UTF_8.name())
        }
        val manifest = try {
            json.decodeFromString<BackupManifest>(text)
        } catch (e: SerializationException) {
            throw BackupException.CorruptBackup("Malformed remote manifest.json", e)
        } catch (e: IllegalArgumentException) {
            throw BackupException.CorruptBackup("Invalid remote manifest.json", e)
        }
        manifest.validate()
        return manifest
    }

    /**
     * Obtiene un token y ejecuta [block]. Si el servidor responde 401/403 con un token en caché,
     * lo invalida y reintenta una vez; si sigue fallando por autorización, registra `driveNeedsReauth`
     * para mostrar el aviso en la UI (garantía 13).
     */
    private suspend fun <T> withValidToken(block: suspend (String) -> T): T {
        val firstToken = try {
            tokenProvider.getAccessToken()
        } catch (e: BackupException.AuthRequired) {
            markReauthIfEnabled()
            throw e
        }
        return try {
            block(firstToken)
        } catch (e: BackupException.AuthRequired) {
            tokenProvider.invalidateToken(firstToken)
            val refreshedToken = try {
                tokenProvider.getAccessToken()
            } catch (reauthError: BackupException.AuthRequired) {
                markReauthIfEnabled()
                throw reauthError
            }
            try {
                block(refreshedToken)
            } catch (secondError: BackupException.AuthRequired) {
                markReauthIfEnabled()
                throw secondError
            }
        }
    }

    private suspend fun markReauthIfEnabled() {
        if (preferences.backupSettings.first().driveEnabled) {
            preferences.recordDriveAuthRevoked()
        }
    }

    private suspend fun copyStream(input: InputStream, out: OutputStream, buffer: ByteArray) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_MANIFEST_BYTES = 32 * 1024 * 1024
        private const val DEFAULT_MIME_TYPE = "application/octet-stream"
    }
}
