package com.joasasso.paperlink.data.backup

import com.joasasso.paperlink.data.files.AttachmentStore
import com.joasasso.paperlink.data.preferences.UserPreferencesRepository
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Resumen de un respaldo exitoso en Google Drive. */
data class DriveBackupSummary(
    val timestamp: Long,
    val totalSizeBytes: Long,
    val uploadedFiles: Int,
    val totalLinks: Int,
    /** Lo que se sumó al teléfono desde Drive antes de respaldar (null si no hizo falta unir). */
    val merged: MergeCounts? = null,
)

/** Programa o cancela la ejecución periódica o automática del respaldo en segundo plano. */
interface BackupScheduler {
    fun scheduleDaily(chargingOnly: Boolean)

    /**
     * Encola una sincronización automática con Wi-Fi (`UNMETERED`).
     * @param debounce si es true, espera unos segundos antes de arrancar para agrupar varios
     *   cambios seguidos (por ejemplo, sacar varias fotos o agregar una etiqueta).
     */
    fun scheduleSync(debounce: Boolean = false) = Unit
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
 * - Un respaldo nunca achica lo que hay en Drive: si Drive tiene un respaldo que este teléfono no
 *   fusionó, [backupNow] primero lo une (solo agrega) y recién después sube (ver [RemoteState]).
 *
 * Los archivos se suben y se bajan de a [transferParallelism] a la vez: con fotos de pocos cientos
 * de KB casi todo el tiempo es espera de la red, no transferencia (Pixel 8 en Wi-Fi, 20 archivos de
 * 300 KB: 2,3 s por archivo de a uno con subida reanudable; 0,23 s con 8 a la vez en un pedido).
 */
class DriveBackupManager(
    private val store: AttachmentStore,
    private val repository: LinkRepository,
    private val preferences: UserPreferencesRepository,
    private val tokenProvider: DriveTokenProvider,
    private val remote: DriveRemoteStore,
    private val scheduler: BackupScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val transferParallelism: Int = 8,
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

        /** Drive tiene un respaldo que este teléfono no fusionó: se une antes de respaldar. */
        data object NeedsMerge : RemoteState
    }

    /** Activa el respaldo periódico en Google Drive con las preferencias actuales. */
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
     * Registra que hubo cambios locales pendientes de subir y, si el respaldo automático está
     * activo, encola una sincronización por Wi-Fi (con una breve ventana para agrupar cambios).
     */
    suspend fun onLocalChange() {
        preferences.setDrivePendingSync(true)
        if (preferences.backupSettings.first().driveEnabled) {
            scheduler.scheduleSync(debounce = true)
        }
    }

    /**
     * Al abrir la app con el respaldo activo, encola una sincronización por Wi-Fi para traer
     * posibles cambios hechos en otro dispositivo y subir lo que haya quedado pendiente.
     */
    suspend fun onAppOpened() {
        if (preferences.backupSettings.first().driveEnabled) {
            scheduler.scheduleSync(debounce = false)
        }
    }

    /**
     * Comprueba que haya permiso para usar Drive. Si hace falta el consentimiento del usuario lanza
     * [BackupException.AuthRequired] con la pantalla de Google para mostrar (antes de pasar a
     * segundo plano, donde no se puede pedir).
     */
    suspend fun ensureAuthorized() {
        withValidToken { }
    }

    /**
     * Consulta el estado del respaldo en `appDataFolder` sin modificar nada (ni local ni remoto).
     */
    suspend fun checkRemoteState(): RemoteState = withValidToken { token ->
        remoteStateOf(remote.listFiles(token))
    }

    /**
     * Ejecuta un respaldo incremental ahora:
     * 1. Si Drive tiene un respaldo que este teléfono no fusionó, primero lo une a la base local
     *    (nunca se pisa contenido que el teléfono no tiene). [onProgress] informa `merging = true`.
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
        withValidToken { token -> restoreWithManifestLocked(token, remote.listFiles(token), onProgress).first }
    }

    /**
     * Sincronización incremental automática (al agregar contenido, al conectar Wi-Fi o al abrir la
     * app):
     * 1. Si Drive tiene un respaldo que este teléfono no fusionó ([RemoteState.NeedsMerge]), lo
     *    descarga y une a la base local.
     * 2. Si hay cambios locales pendientes (`pendingSync`), o si este teléfono tiene códigos o
     *    elementos que no estaban en el respaldo remoto, sube lo nuevo a Drive.
     * 3. Si entraron cambios locales mientras subía, repite una pasada para no dejar nada afuera.
     *
     * Si este teléfono ya está al día con Drive y no hay cambios locales, termina tras listar
     * `appDataFolder` sin subir un `manifest.json` redundante.
     */
    suspend fun syncNow() = mutex.withLock {
        withValidToken { token -> syncLocked(token) }
    }

    private suspend fun remoteStateOf(remoteFiles: List<RemoteDriveFile>): RemoteState {
        // listFiles ordena del más nuevo al más viejo: el primero es el respaldo vigente.
        val manifestFile = remoteFiles.firstOrNull { it.name == BackupManifest.MANIFEST_FILE_NAME }
            ?: return RemoteState.None
        return if (manifestFile.id == preferences.backupSettings.first().syncedManifestId) {
            RemoteState.InSync
        } else {
            RemoteState.NeedsMerge
        }
    }

    private suspend fun syncLocked(token: String) {
        var firstPass = true
        while (firstPass || preferences.backupSettings.first().pendingSync) {
            firstPass = false
            val remoteFiles = remote.listFiles(token)
            val state = remoteStateOf(remoteFiles)
            val remoteManifest = if (state == RemoteState.NeedsMerge) {
                restoreWithManifestLocked(token, remoteFiles, onProgress = {}).second
            } else {
                null
            }
            val shouldUpload = when {
                preferences.backupSettings.first().pendingSync -> true
                state == RemoteState.None -> repository.exportSnapshot(includeMissingFiles = true).links.isNotEmpty()
                remoteManifest != null -> hasUnbackedLocalChanges(remoteManifest)
                else -> false
            }
            if (shouldUpload) {
                uploadSnapshotLocked(token, remoteFiles, merged = null, onProgress = {})
            }
        }
    }

    private suspend fun hasUnbackedLocalChanges(remoteManifest: BackupManifest): Boolean {
        val local = repository.exportSnapshot(includeMissingFiles = true)
        val remoteLinks = remoteManifest.links.associateBy { it.code }
        if (local.links.any { link -> link.code !in remoteLinks || remoteLinks[link.code]?.label != link.label }) {
            return true
        }
        val remoteAttachmentIds = remoteManifest.attachments.mapTo(HashSet()) { it.id }
        return local.attachments.any { it.id !in remoteAttachmentIds }
    }

    private suspend fun backupLocked(token: String, onProgress: (BackupProgress) -> Unit): DriveBackupSummary {
        val remoteFiles = remote.listFiles(token)
        val merged = if (remoteStateOf(remoteFiles) == RemoteState.NeedsMerge) {
            restoreWithManifestLocked(token, remoteFiles) { onProgress(it.copy(merging = true)) }.first
        } else {
            null
        }
        return uploadSnapshotLocked(token, remoteFiles, merged, onProgress)
    }

    private suspend fun uploadSnapshotLocked(
        token: String,
        remoteFiles: List<RemoteDriveFile>,
        merged: MergeCounts?,
        onProgress: (BackupProgress) -> Unit,
    ): DriveBackupSummary {
        val wasPending = preferences.backupSettings.first().pendingSync
        if (wasPending) preferences.setDrivePendingSync(false)
        try {
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
            val started = clock()
            inParallel(toUpload) { path, done ->
                val length = store.length(path)
                store.readFile(path) { input ->
                    remote.uploadStream(token, path, mimeByPath[path] ?: DEFAULT_MIME_TYPE, length, input)
                }
                onProgress(BackupProgress(done(), toUpload.size))
            }
            log("Drive backup: ${toUpload.size} files uploaded in ${clock() - started} ms")

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

            // Limpieza posterior al commit del nuevo manifest en paralelo: si falla no invalida el respaldo ya subido.
            val referencedPaths = kept.mapNotNullTo(HashSet()) { it.relativePath }
            val obsoleteFiles = remoteFiles.filter { oldFile ->
                val isOldManifest = oldFile.name == BackupManifest.MANIFEST_FILE_NAME && oldFile.id != uploadedManifest.id
                val isOrphanMedia = oldFile.name != BackupManifest.MANIFEST_FILE_NAME && oldFile.name !in referencedPaths
                isOldManifest || isOrphanMedia
            }
            inParallel(obsoleteFiles) { oldFile, _ ->
                try {
                    remote.deleteFile(token, oldFile.id)
                } catch (e: BackupException) {
                    log("Could not delete old Drive file ${oldFile.name}", e)
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
                merged = merged,
            )
        } catch (t: Throwable) {
            if (wasPending) {
                withContext(NonCancellable) { preferences.setDrivePendingSync(true) }
            }
            throw t
        }
    }

    private suspend fun restoreWithManifestLocked(
        token: String,
        remoteFiles: List<RemoteDriveFile>,
        onProgress: (BackupProgress) -> Unit,
    ): Pair<MergeCounts, BackupManifest> {
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
            inParallel(fileAttachments) { att, done ->
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
                            copyStream(input, out, ByteArray(BUFFER_SIZE))
                        }
                    }
                    if (created) synchronized(newlyRestoredPaths) { newlyRestoredPaths += path }
                }
                onProgress(BackupProgress(done(), fileAttachments.size))
            }
            val counts = repository.commitBackup(manifest, newlyRestoredPaths)
            preferences.recordDriveRestore(manifestFile.id)
            return counts to manifest
        } catch (t: Throwable) {
            repository.discardRestoredFiles(newlyRestoredPaths)
            throw t
        }
    }

    /**
     * Ejecuta [block] para cada elemento, de a [transferParallelism] a la vez. `done()` cuenta los
     * terminados (para el progreso). Si uno falla se cancelan los demás y se propaga el error.
     */
    private suspend fun <T> inParallel(items: Collection<T>, block: suspend (T, done: () -> Int) -> Unit) {
        val semaphore = Semaphore(transferParallelism)
        val finished = AtomicInteger()
        coroutineScope {
            items.map { item ->
                async {
                    semaphore.withPermit {
                        currentCoroutineContext().ensureActive()
                        block(item) { finished.incrementAndGet() }
                    }
                }
            }.awaitAll()
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
