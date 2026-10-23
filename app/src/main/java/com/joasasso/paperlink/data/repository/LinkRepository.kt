package com.joasasso.paperlink.data.repository

import android.net.Uri
import com.joasasso.paperlink.data.files.AttachmentStore
import com.joasasso.paperlink.data.local.Attachment
import com.joasasso.paperlink.data.local.Link
import com.joasasso.paperlink.data.local.LinkDao
import com.joasasso.paperlink.data.local.LinkWithAttachments
import com.joasasso.paperlink.domain.CodeAlphabet
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
) {

    // --- Lectura ---

    fun observeLink(code: String): Flow<LinkWithAttachments?> =
        dao.observeLink(CodeAlphabet.normalize(code)).map { it?.ordered() }

    suspend fun getLink(code: String): LinkWithAttachments? =
        dao.getLink(CodeAlphabet.normalize(code))?.ordered()

    fun observeRecent(limit: Int = RECENT_LIMIT): Flow<List<LinkWithAttachments>> =
        dao.observeRecent(limit).map { list -> list.map { it.ordered() } }

    fun searchByLabel(query: String): Flow<List<LinkWithAttachments>> =
        dao.searchByLabel("%${query.trim().escapeLike()}%").map { list -> list.map { it.ordered() } }

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
                if (dao.insertLinkWithAttachments(link, attachments)) return code
                log("Code collision, retrying")
            }
            throw CodeSpaceExhaustedException()
        } catch (t: Throwable) {
            discardFiles(prepared.importedPaths)
            throw t
        }
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
        } catch (t: Throwable) {
            discardFiles(prepared.importedPaths)
            throw t
        }
    }

    suspend fun setLabel(code: String, label: String?) {
        val normalized = CodeAlphabet.normalize(code)
        if (dao.updateLabel(normalized, label.normalizeLabel(), clock()) == 0) throw LinkNotFoundException(normalized)
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

    /** Elimina físicamente los códigos borrados hace más de [UNDO_WINDOW_MS]. */
    suspend fun purgeExpired() {
        val paths = dao.purgeDeletedBefore(clock() - UNDO_WINDOW_MS)
        deleteFiles(paths)
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
        withContext(NonCancellable) { deleteFiles(paths) }
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

private fun LinkWithAttachments.ordered() = copy(attachments = attachments.sortedBy { it.position })

private fun String?.normalizeLabel(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun String.escapeLike(): String =
    replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
