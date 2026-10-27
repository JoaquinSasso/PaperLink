package com.joasasso.paperlink.data.backup

import com.joasasso.paperlink.data.files.AttachmentStore
import com.joasasso.paperlink.data.preferences.UserPreferencesRepository
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * - Un respaldo nunca achica lo que hay en Drive sin que el usuario lo haya visto: si Drive tiene
 *   un respaldo que este teléfono no fusionó, [backupNow] se niega (ver [RemoteState]).
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

    /** Un solo respaldo o restauración a la vez: la limpieza de uno no puede pisar los archivos del otro. */
    private val mutex = Mutex()

    /** Qué hay en Google Drive respecto de este teléfono. */
    sealed interface RemoteState {
        /** No hay respaldo (o está vacío): se puede respaldar directamente. */
        data object None : RemoteState

        /** El último respaldo de Drive lo subió o restauró este teléfono: se puede respaldar encima. */
        data object InSync : RemoteState

        /** Drive tiene un respaldo que este teléfono no fusionó: hay que restaurarlo antes de respaldar. */
        data class NeedsMerge(val info: RemoteBackupInfo) : RemoteState
    }

    /**
     * Activa el respaldo periódico en Google Drive con las preferencias actuales. Llamarlo recién
     * cuando [checkRemoteState] no devuelve [RemoteState.NeedsMerge] (o después de restaurar).
     */
    suspend fun enableDriveBackup() {
        val settings = preferences.backupSettings.first()
        preferences.setDriveBackupEnabled(true, clock())
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
     * Consulta el estado del respaldo en `appDataFolder` sin modificar nada (ni local ni remoto).
     */
    suspend fun checkRemoteState(): RemoteState = withValidToken { token ->
        remoteStateOf(token, remote.listFiles(token))
    }

    /**
     * Ejecuta un respaldo incremental ahora:
     * 1. Si Drive tiene un respaldo que este teléfono no fusionó, no hace nada y lanza
     *    [BackupException.RemoteNotMerged] (nunca se pisa contenido que el teléfono no tiene).
     * 2. Sube solo los archivos de `media/` que todavía no estén en `appDataFolder`.
     * 3. Sube el nuevo `manifest.json` al final.
     * 4. Recién después borra los manifests viejos y los archivos que ninguna fila referencia.
     *
     * Las filas cuyo archivo falta en el teléfono pero está en Drive se conservan en el manifest
     * (y su archivo en Drive): un archivo perdido localmente no borra la copia de respaldo.
     */
    suspend fun backupNow(
        onProgress: (BackupProgress) -> Unit = {},
    ): DriveBackupSummary = mutex.withLock {
        withValidToken { token -> backupLocked(token, onProgress) }
    }

    /**
     * Descarga los archivos faltantes desde `appDataFolder`, verifica su SHA-256 y fusiona
     * el `manifest.json` con la base local sin pisar nada (garantías 12 y 14).
     */
    suspend fun restoreFromDrive(
        onProgress: (BackupProgress) -> Unit = {},
    ): MergeCounts = mutex.withLock {
        withValidToken { token -> restoreLocked(token, onProgress) }
    }

    private suspend fun remoteStateOf(token: String, remoteFiles: List<RemoteDriveFile>): RemoteState {
        // listFiles ordena del más nuevo al más viejo: el primero es el respaldo vigente.
        val manifestFile = remoteFiles.firstOrNull { it.name == BackupManifest.MANIFEST_FILE_NAME }
            ?: return RemoteState.None
        if (manifestFile.id == preferences.backupSettings.first().syncedManifestId) return RemoteState.InSync
        val manifest = downloadManifest(token, manifestFile.id)
        if (manifest.links.isEmpty()) return RemoteState.None
        return RemoteState.NeedsMerge(
            RemoteBackupInfo(
                createdAt = manifest.createdAt,
                linkCount = manifest.links.size,
                attachmentCount = manifest.attachments.size,
                totalSizeBytes = manifest.attachments.sumOf { it.sizeBytes ?: 0L } + (manifestFile.size ?: 0L),
            )
        )
    }

    private suspend fun backupLocked(token: String, onProgress: (BackupProgress) -> Unit): DriveBackupSummary {
        val remoteFiles = remote.listFiles(token)
        val state = remoteStateOf(token, remoteFiles)
        if (state is RemoteState.NeedsMerge) {
            preferences.recordRemotePending()
            throw BackupException.RemoteNotMerged(state.info)
        }
        val existingNames = remoteFiles.mapTo(HashSet()) { it.name }

        // Cada archivo en media/<uuid>.<ext> es inmutable; solo se sube si no está ya en Drive.
        val snapshot = repository.exportSnapshot(includeMissingFiles = true)
        val toUpload = LinkedHashSet<String>()
        val kept = snapshot.attachments.filter { att ->
            val path = att.relativePath ?: return@filter true
            when {
                store.exists(path) -> {
                    if (path !in existingNames) toUpload += path
                    true
                }
                // Falta en el teléfono pero sigue en Drive: se conserva (no se borra la copia).
                path in existingNames -> true
                else -> {
                    log("File missing locally and in Drive, left out of the backup: $path")
                    false
                }
            }
        }
        val manifest = snapshot.copy(attachments = kept)
        val mimeByPath = kept.filter { it.relativePath != null }.associate { it.relativePath!! to it.mimeType }

        onProgress(BackupProgress(0, toUpload.size))
        toUpload.forEachIndexed { index, path ->
            currentCoroutineContext().ensureActive()
            val length = store.length(path)
            store.readFile(path) { input ->
                remote.uploadStream(token, path, mimeByPath[path] ?: DEFAULT_MIME_TYPE, length, input)
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
            contentLength = manifestBytes.size.toLong(),
            contentStream = ByteArrayInputStream(manifestBytes),
        )

        // Limpieza posterior al commit del nuevo manifest: si falla no invalida el respaldo ya subido.
        val referencedPaths = kept.mapNotNullTo(HashSet()) { it.relativePath }
        for (oldFile in remoteFiles) {
            val isOldManifest = oldFile.name == BackupManifest.MANIFEST_FILE_NAME && oldFile.id != uploadedManifest.id
            val isOrphanMedia = oldFile.name != BackupManifest.MANIFEST_FILE_NAME && oldFile.name !in referencedPaths
            if (isOldManifest || isOrphanMedia) {
                try {
                    remote.deleteFile(token, oldFile.id)
                } catch (e: BackupException) {
                    log("Could not delete old Drive file ${oldFile.name}", e)
                }
            }
        }

        val totalSize = manifestBytes.size.toLong() +
            kept.filter { it.relativePath != null }.distinctBy { it.relativePath }.sumOf { it.sizeBytes ?: 0L }
        val now = clock()
        preferences.recordDriveBackupSuccess(now, totalSize, uploadedManifest.id)

        return DriveBackupSummary(
            timestamp = now,
            totalSizeBytes = totalSize,
            uploadedFiles = toUpload.size,
            totalLinks = manifest.links.size,
        )
    }

    private suspend fun restoreLocked(token: String, onProgress: (BackupProgress) -> Unit): MergeCounts {
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
            val counts = repository.commitBackup(manifest, newlyRestoredPaths)
            preferences.recordDriveRestore(manifestFile.id)
            return counts
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
