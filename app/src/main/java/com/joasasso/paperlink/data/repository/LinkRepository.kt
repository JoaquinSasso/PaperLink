package com.joasasso.paperlink.data.repository

import android.net.Uri
import com.joasasso.paperlink.data.backup.BackupAttachment
import com.joasasso.paperlink.data.backup.BackupException
import com.joasasso.paperlink.data.backup.BackupLink
import com.joasasso.paperlink.data.backup.BackupManifest
import com.joasasso.paperlink.data.backup.MergeCounts
import com.joasasso.paperlink.data.backup.toBackup
import com.joasasso.paperlink.data.files.AttachmentStore
import com.joasasso.paperlink.data.local.Attachment
import com.joasasso.paperlink.data.local.Link
import com.joasasso.paperlink.data.local.LinkDao
import com.joasasso.paperlink.data.local.LinkWithAttachments
import com.joasasso.paperlink.domain.CodeAlphabet
import com.joasasso.paperlink.domain.labelMatches
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.random.Random

/** Lo que se puede vincular a un código. */
sealed interface AttachmentSource {
    /** Archivo de cámara, galería, compartir o selector: se copia a `media/`. */
    data class Content(val uri: Uri) : AttachmentSource

    /**
     * Archivo ya copiado a `media/` (por ejemplo, al recibir algo compartido). Es de quien lo
     * importó: si la operación falla no se borra, para poder reintentar con otro destino.
     */
    data class Stored(val file: AttachmentStore.ImportResult) : AttachmentSource
    data class WebUrl(val url: String) : AttachmentSource
    data class Note(val text: String) : AttachmentSource
}

/** Progreso de importación: archivo [fileIndex] de [fileCount] (solo cuentan los [AttachmentSource.Content]). */
data class ImportProgress(val fileIndex: Int, val fileCount: Int, val copiedBytes: Long, val totalBytes: Long?)

/** Un código listo para mostrar: sus elementos y cuáles faltan en disco. */
data class LinkContent(val link: LinkWithAttachments, val unavailableIds: Set<String>)

class LinkNotFoundException(code: String) : NoSuchElementException("Link $code not found")

class CodeSpaceExhaustedException : IllegalStateException("No free code after ${CodeAlphabet.MAX_RETRIES} attempts")

/**
 * Códigos y sus attachments. Coordina la base ([LinkDao]) con el disco ([AttachmentStore]):
 * los archivos se copian primero y recién después se inserta en una transacción; si la
 * inserción falla, los archivos copiados se borran. Nunca queda una fila sin archivo ni al revés.
 *
 * @param externalScope scope que sobrevive a la pantalla, para la purga diferida del borrado.
 */
class LinkRepository(
    private val dao: LinkDao,
    private val store: AttachmentStore,
    private val externalScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val onChanged: () -> Unit = {},
) {

    // --- Lectura ---

    fun observeLink(code: String): Flow<LinkWithAttachments?> =
        dao.observeLink(CodeAlphabet.normalize(code)).map { it?.visible() }

    suspend fun getLink(code: String): LinkWithAttachments? =
        dao.getLink(CodeAlphabet.normalize(code))?.visible()

    fun observeRecent(limit: Int = RECENT_LIMIT): Flow<List<LinkWithAttachments>> =
        dao.observeRecent(limit).map { list -> list.map { it.visible() } }

    /** Códigos cuya etiqueta contiene [query], sin distinguir tildes ni mayúsculas. */
    fun searchByLabel(query: String): Flow<List<LinkWithAttachments>> =
        dao.observeLabeled().map { list ->
            list.filter { labelMatches(it.link.label, query) }.map { it.visible() }
        }

    /** Todos los códigos vigentes (para sugerir parecidos cuando uno no existe). */
    suspend fun allCodes(): List<String> = dao.allCodes()

    /** Archivo de un attachment (null si no es un archivo). Solo arma la ruta: no toca el disco. */
    fun fileFor(attachment: Attachment): File? = attachment.relativePath?.let(store::fileFor)

    /** URI para abrir el archivo con otra app (vía FileProvider). */
    fun uriFor(attachment: Attachment): Uri? = attachment.relativePath?.let(store::uriFor)

    /** false si el attachment es un archivo que ya no está en disco. */
    suspend fun isAvailable(attachment: Attachment): Boolean =
        attachment.relativePath?.let { store.exists(it) } ?: true

    /**
     * El código con sus elementos y los ids de los que faltan en disco (se muestran como
     * "archivo no disponible" sin ocultar el código). null si el código no existe.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeLinkContent(code: String): Flow<LinkContent?> =
        observeLink(code).mapLatest { link ->
            link?.let { LinkContent(it, it.attachments.filterNot { a -> isAvailable(a) }.map { a -> a.id }.toSet()) }
        }

    fun observeCount(): Flow<Int> = dao.observeCount()

    fun observeLastCreatedCode(): Flow<String?> = dao.observeLastCreatedCode()

    // --- Escritura ---

    /**
     * Crea un código nuevo con [sources]. El código se genera e inserta directamente;
     * si choca con uno existente se reintenta con otro (nunca "verificar y después insertar").
     *
     * @return el código creado, ya guardado en la base.
     */
    suspend fun createLink(
        sources: List<AttachmentSource>,
        label: String? = null,
        onProgress: (ImportProgress) -> Unit = {},
    ): String {
        require(sources.isNotEmpty()) { "A link needs at least one attachment" }
        val now = clock()
        val prepared = prepare(sources, now, onProgress)
        try {
            repeat(CodeAlphabet.MAX_RETRIES) {
                val code = CodeAlphabet.generate(random)
                val link = Link(code, label.normalizeLabel(), createdAt = now, updatedAt = now, deletedAt = null)
                val attachments = prepared.attachments.mapIndexed { i, a -> a.copy(linkCode = code, position = i) }
                if (dao.insertLinkWithAttachments(link, attachments)) {
                    onChanged()
                    return code
                }
                log("Code collision, retrying")
            }
            throw CodeSpaceExhaustedException()
        } catch (t: Throwable) {
            discardFiles(prepared.importedPaths)
            throw t
        }
    }

    /**
     * Crea un código sin elementos: sus elementos llegan después (por ejemplo, fotos de la galería
     * en segundo plano), así el usuario puede anotarlo enseguida. El código ya queda guardado.
     * Mismo reintento ante choque que [createLink].
     */
    suspend fun createEmptyLink(): String {
        val now = clock()
        repeat(CodeAlphabet.MAX_RETRIES) {
            val code = CodeAlphabet.generate(random)
            if (dao.insertLinkOrIgnore(Link(code, null, createdAt = now, updatedAt = now, deletedAt = null)) != -1L) {
                onChanged()
                return code
            }
            log("Code collision, retrying")
        }
        throw CodeSpaceExhaustedException()
    }

    /** Agrega [sources] al final de un código existente. */
    suspend fun addAttachments(
        code: String,
        sources: List<AttachmentSource>,
        onProgress: (ImportProgress) -> Unit = {},
    ) {
        require(sources.isNotEmpty()) { "Nothing to add" }
        val normalized = CodeAlphabet.normalize(code)
        val now = clock()
        val prepared = prepare(sources, now, onProgress)
        try {
            if (!dao.appendAttachments(normalized, prepared.attachments, now)) throw LinkNotFoundException(normalized)
            onChanged()
        } catch (t: Throwable) {
            discardFiles(prepared.importedPaths)
            throw t
        }
    }

    /** Si la etiqueta no cambió no escribe nada: así abrir y cerrar un código no lo sube en recientes. */
    suspend fun setLabel(code: String, label: String?) {
        val normalized = CodeAlphabet.normalize(code)
        val newLabel = label.normalizeLabel()
        val current = dao.getRawLink(normalized)?.takeIf { it.deletedAt == null } ?: throw LinkNotFoundException(normalized)
        if (current.label == newLabel) return
        if (dao.updateLabel(normalized, newLabel, clock()) == 0) throw LinkNotFoundException(normalized)
        onChanged()
    }

    // --- Borrado ---

    /**
     * Borrado lógico: el código desaparece de inmediato pero puede restaurarse con [undoDelete]
     * durante [UNDO_WINDOW_MS]. Pasada la ventana se purga (filas y archivos).
     */
    suspend fun delete(code: String) {
        val normalized = CodeAlphabet.normalize(code)
        if (dao.softDelete(normalized, clock()) == 0) throw LinkNotFoundException(normalized)
        externalScope.launch {
            delay(UNDO_WINDOW_MS)
            purgeExpired()
        }
    }

    /** @return false si ya se purgó o no estaba borrado. */
    suspend fun undoDelete(code: String): Boolean = dao.restore(CodeAlphabet.normalize(code)) > 0

    /**
     * Borrado lógico de un elemento suelto, con la misma ventana de deshacer que los códigos.
     * @return false si no existía o ya estaba borrado.
     */
    suspend fun deleteAttachment(id: String): Boolean {
        if (dao.softDeleteAttachment(id, clock()) == 0) return false
        externalScope.launch {
            delay(UNDO_WINDOW_MS)
            purgeExpired()
        }
        return true
    }

    /** @return false si ya se purgó o no estaba borrado. */
    suspend fun undoDeleteAttachment(id: String): Boolean = dao.restoreAttachment(id) > 0

    /** Elimina físicamente códigos y elementos borrados hace más de [UNDO_WINDOW_MS]. */
    suspend fun purgeExpired() {
        val cutoff = clock() - UNDO_WINDOW_MS
        val links = dao.purgeDeletedBefore(cutoff)
        val attachments = dao.purgeAttachmentsDeletedBefore(cutoff)
        deleteFiles(links.filePaths + attachments.filePaths)
        if (links.deletedCount + attachments.deletedCount > 0) {
            onChanged()
        }
    }

    /**
     * Tareas de arranque: purga los borrados vencidos y elimina archivos huérfanos y `.part`
     * que hayan quedado de una importación interrumpida.
     */
    suspend fun runMaintenance() {
        purgeExpired()
        val removed = store.sweep(dao.allFilePaths().toSet(), clock())
        if (removed > 0) log("Maintenance removed $removed orphan files")
    }

    // --- Respaldo ---

    /**
     * Arma el snapshot de todos los códigos y attachments vigentes.
     *
     * @param includeMissingFiles incluir también los attachments cuyo archivo falta en disco (el
     *   respaldo de Drive los conserva si su copia remota existe). El ZIP no los incluye porque no
     *   tiene de dónde sacar el archivo.
     */
    suspend fun exportSnapshot(includeMissingFiles: Boolean = false): BackupManifest {
        val activeLinks = dao.getAllActiveLinks().map { it.visible() }
        val links = mutableListOf<BackupLink>()
        val attachments = mutableListOf<BackupAttachment>()
        for (item in activeLinks) {
            val validAttachments = if (includeMissingFiles) item.attachments else item.attachments.filter { att ->
                val available = isAvailable(att)
                if (!available) log("Skipping missing file in backup: ${att.relativePath}")
                available
            }
            // También los códigos sin elementos: el código está anotado en papel y debe seguir
            // reservado después de restaurar.
            run {
                links += item.link.toBackup()
                validAttachments.mapIndexedTo(attachments) { index, att ->
                    att.copy(position = index).toBackup()
                }
            }
        }
        return BackupManifest(
            createdAt = clock(),
            links = links,
            attachments = attachments,
        )
    }

    /**
     * Verifica que todos los archivos de [manifest] estén en disco con su SHA-256 correcto y
     * fusiona las filas en una transacción. Si algo falla, borra [newlyRestoredPaths].
     */
    suspend fun commitBackup(manifest: BackupManifest, newlyRestoredPaths: List<String>): MergeCounts {
        try {
            manifest.validate()
            for (att in manifest.attachments) {
                val path = att.relativePath ?: continue
                if (!store.hasMatchingFile(path, att.sha256)) {
                    throw BackupException.CorruptBackup("Missing or corrupt file after restore: $path")
                }
            }
            return dao.mergeBackup(
                links = manifest.links.map { it.toEntity() },
                attachments = manifest.attachments.map { it.toEntity() },
            )
        } catch (t: Throwable) {
            discardFiles(newlyRestoredPaths)
            throw t
        }
    }

    /** Limpia archivos recién restaurados si la restauración se interrumpe antes de llegar a la base. */
    suspend fun discardRestoredFiles(paths: List<String>) {
        discardFiles(paths)
    }

    // --- Internos ---

    /** Attachments listos para insertar y las rutas copiadas en esta llamada (para limpiar si falla). */
    private class Prepared(val attachments: List<Attachment>, val importedPaths: List<String>)

    /** Valida todo antes de copiar y luego importa los archivos. Si algo falla, no deja archivos. */
    private suspend fun prepare(
        sources: List<AttachmentSource>,
        now: Long,
        onProgress: (ImportProgress) -> Unit,
    ): Prepared {
        for (source in sources) {
            when (source) {
                is AttachmentSource.WebUrl -> require(source.url.isNotBlank()) { "Empty URL" }
                is AttachmentSource.Note -> require(source.text.isNotBlank()) { "Empty note" }
                is AttachmentSource.Content, is AttachmentSource.Stored -> Unit
            }
        }
        val fileCount = sources.count { it is AttachmentSource.Content }
        val attachments = mutableListOf<Attachment>()
        val imported = mutableListOf<String>()
        try {
            for (source in sources) {
                attachments += when (source) {
                    is AttachmentSource.Content -> {
                        val index = imported.size
                        val result = store.import(source.uri) { copied, total ->
                            onProgress(ImportProgress(index, fileCount, copied, total))
                        }
                        imported += result.relativePath
                        fileAttachment(now, result)
                    }
                    is AttachmentSource.Stored -> fileAttachment(now, source.file)
                    is AttachmentSource.WebUrl -> newAttachment(now, Attachment.Kind.URL).copy(url = source.url.trim())
                    is AttachmentSource.Note -> newAttachment(now, Attachment.Kind.NOTE).copy(text = source.text.trim())
                }
            }
        } catch (t: Throwable) {
            discardFiles(imported)
            throw t
        }
        return Prepared(attachments, imported)
    }

    private fun fileAttachment(now: Long, file: AttachmentStore.ImportResult) =
        newAttachment(now, file.kind).copy(
            relativePath = file.relativePath,
            mimeType = file.mimeType,
            sizeBytes = file.sizeBytes,
            sha256 = file.sha256,
        )

    private fun newAttachment(now: Long, kind: Attachment.Kind) = Attachment(
        id = UUID.randomUUID().toString(),
        linkCode = "",
        kind = kind,
        relativePath = null,
        url = null,
        text = null,
        mimeType = null,
        sizeBytes = null,
        sha256 = null,
        position = 0,
        createdAt = now,
    )

    /** Borra archivos copiados que no llegaron a la base. Corre aunque se haya cancelado. */
    private suspend fun discardFiles(paths: List<String>) {
        discardUnreferenced(paths)
    }

    /**
     * Borra archivos importados que no llegaron a la base. Solo borra los que ninguna fila
     * referencia: una operación de Room puede confirmarse y aun así lanzar la cancelación al
     * volver, y borrar ese archivo dejaría una fila sin su archivo (garantía 1). Corre aunque se
     * haya cancelado.
     */
    suspend fun discardUnreferenced(paths: List<String>) {
        withContext(NonCancellable) {
            deleteFiles(paths.filterNot { path ->
                dao.isFileReferenced(path).also { if (it) log("Kept $path: already referenced") }
            })
        }
    }

    /** Si un archivo no se puede borrar, el barrido de huérfanos lo reintenta en el próximo arranque. */
    private suspend fun deleteFiles(paths: List<String>) {
        for (path in paths) {
            if (!store.delete(path)) log("Could not delete $path; left for the orphan sweep")
        }
    }

    companion object {
        const val UNDO_WINDOW_MS = 15_000L
        const val RECENT_LIMIT = 50
    }
}

/** Sin los attachments borrados y en orden. */
private fun LinkWithAttachments.visible() =
    copy(attachments = attachments.filter { it.deletedAt == null }.sortedBy { it.position })

private fun String?.normalizeLabel(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
