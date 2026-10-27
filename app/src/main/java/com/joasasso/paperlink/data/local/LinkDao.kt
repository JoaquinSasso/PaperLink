package com.joasasso.paperlink.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.joasasso.paperlink.data.backup.MergeCounts
import kotlinx.coroutines.flow.Flow
import kotlin.math.max

/**
 * Acceso a [Link] y [Attachment]. Las lecturas excluyen los códigos con borrado lógico; los
 * attachments borrados se filtran en el repositorio (`@Relation` no admite condiciones).
 */
@Dao
interface LinkDao {

    // --- Escritura ---

    /** Devuelve -1 si el código ya existe (incluso si está borrado lógicamente). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLinkOrIgnore(link: Link): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttachments(attachments: List<Attachment>)

    /**
     * Inserta el código y sus attachments en una sola transacción.
     * Devuelve false (sin escribir nada) si el código ya existe.
     */
    @Transaction
    suspend fun insertLinkWithAttachments(link: Link, attachments: List<Attachment>): Boolean {
        if (insertLinkOrIgnore(link) == -1L) return false
        insertAttachments(attachments)
        return true
    }

    /**
     * Agrega attachments al final de un código existente, asignando posiciones consecutivas.
     * Devuelve false (sin escribir nada) si el código no existe o está borrado.
     */
    @Transaction
    suspend fun appendAttachments(code: String, attachments: List<Attachment>, now: Long): Boolean {
        if (touch(code, now) == 0) return false
        val start = maxPosition(code) + 1
        insertAttachments(attachments.mapIndexed { i, a -> a.copy(linkCode = code, position = start + i) })
        return true
    }

    @Query("UPDATE links SET updatedAt = :now WHERE code = :code AND deletedAt IS NULL")
    suspend fun touch(code: String, now: Long): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM attachments WHERE linkCode = :code")
    suspend fun maxPosition(code: String): Int

    @Query("UPDATE links SET label = :label, updatedAt = :now WHERE code = :code AND deletedAt IS NULL")
    suspend fun updateLabel(code: String, label: String?, now: Long): Int

    // --- Borrado ---

    @Query("UPDATE links SET deletedAt = :now WHERE code = :code AND deletedAt IS NULL")
    suspend fun softDelete(code: String, now: Long): Int

    @Query("UPDATE links SET deletedAt = NULL WHERE code = :code AND deletedAt IS NOT NULL")
    suspend fun restore(code: String): Int

    @Query(
        """
        SELECT a.relativePath FROM attachments a JOIN links l ON a.linkCode = l.code
        WHERE l.deletedAt IS NOT NULL AND l.deletedAt <= :cutoff AND a.relativePath IS NOT NULL
        """
    )
    suspend fun filesOfDeletedBefore(cutoff: Long): List<String>

    /** Los attachments se borran en cascada. */
    @Query("DELETE FROM links WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff")
    suspend fun deleteLinksDeletedBefore(cutoff: Long): Int

    /**
     * Borra físicamente las filas de los códigos borrados antes de [cutoff] y devuelve las
     * rutas de sus archivos, para borrarlas del disco después del commit.
     */
    @Transaction
    suspend fun purgeDeletedBefore(cutoff: Long): PurgeResult {
        val paths = filesOfDeletedBefore(cutoff)
        val count = deleteLinksDeletedBefore(cutoff)
        return PurgeResult(deletedCount = count, filePaths = paths)
    }

    // --- Lectura ---

    @Transaction
    @Query("SELECT * FROM links WHERE code = :code AND deletedAt IS NULL")
    fun observeLink(code: String): Flow<LinkWithAttachments?>

    @Transaction
    @Query("SELECT * FROM links WHERE code = :code AND deletedAt IS NULL")
    suspend fun getLink(code: String): LinkWithAttachments?

    @Transaction
    @Query("SELECT * FROM links WHERE deletedAt IS NULL ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LinkWithAttachments>>

    @Query("SELECT code FROM links WHERE deletedAt IS NULL ORDER BY createdAt DESC LIMIT 1")
    fun observeLastCreatedCode(): Flow<String?>

    @Query("SELECT COUNT(*) FROM links WHERE deletedAt IS NULL")
    fun observeCount(): Flow<Int>

    /** Todas las rutas referenciadas, incluidas las de códigos borrados aún no purgados. */
    @Query("SELECT relativePath FROM attachments WHERE relativePath IS NOT NULL")
    suspend fun allFilePaths(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM attachments WHERE relativePath = :path)")
    suspend fun isFileReferenced(path: String): Boolean

    @Query("SELECT code FROM links WHERE deletedAt IS NULL")
    suspend fun allCodes(): List<String>

    /** Códigos con etiqueta; el filtro (sin tildes ni mayúsculas) se hace en Kotlin. */
    @Transaction
    @Query("SELECT * FROM links WHERE deletedAt IS NULL AND label IS NOT NULL ORDER BY updatedAt DESC")
    fun observeLabeled(): Flow<List<LinkWithAttachments>>

    // --- Elementos sueltos ---

    @Query("UPDATE attachments SET deletedAt = :now WHERE id = :id AND deletedAt IS NULL")
    suspend fun softDeleteAttachment(id: String, now: Long): Int

    @Query("UPDATE attachments SET deletedAt = NULL WHERE id = :id AND deletedAt IS NOT NULL")
    suspend fun restoreAttachment(id: String): Int

    @Query("SELECT relativePath FROM attachments WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff AND relativePath IS NOT NULL")
    suspend fun filesOfAttachmentsDeletedBefore(cutoff: Long): List<String>

    @Query("DELETE FROM attachments WHERE deletedAt IS NOT NULL AND deletedAt <= :cutoff")
    suspend fun deleteAttachmentsDeletedBefore(cutoff: Long): Int

    @Transaction
    suspend fun purgeAttachmentsDeletedBefore(cutoff: Long): PurgeResult {
        val paths = filesOfAttachmentsDeletedBefore(cutoff)
        val count = deleteAttachmentsDeletedBefore(cutoff)
        return PurgeResult(deletedCount = count, filePaths = paths)
    }

    // --- Respaldo ---

    @Transaction
    @Query("SELECT * FROM links WHERE deletedAt IS NULL ORDER BY createdAt ASC")
    suspend fun getAllActiveLinks(): List<LinkWithAttachments>

    @Query("SELECT * FROM links WHERE code = :code")
    suspend fun getRawLink(code: String): Link?

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun getRawAttachment(id: String): Attachment?

    /**
     * Fusiona los códigos y attachments de un respaldo sin pisar los datos existentes (garantía 14).
     * - Si el código no existe, se crea.
     * - Si el código existe (y estaba borrado lógicamente, se restaura), conserva su etiqueta si ya
     *   tenía una; si no tenía, adopta la del respaldo.
     * - Cada attachment del respaldo cuyo `id` no exista se agrega al final del código (`maxPosition + 1`).
     */
    @Transaction
    suspend fun mergeBackup(links: List<Link>, attachments: List<Attachment>): MergeCounts {
        val attachmentsByCode = attachments.groupBy { it.linkCode }
        var addedLinks = 0
        var addedAttachments = 0

        for (incoming in links) {
            val existing = getRawLink(incoming.code)
            if (existing == null) {
                insertLinkOrIgnore(incoming.copy(deletedAt = null))
                addedLinks++
            } else {
                if (existing.deletedAt != null) {
                    restore(incoming.code)
                    addedLinks++
                }
                if (existing.label == null && incoming.label != null) {
                    updateLabel(incoming.code, incoming.label, max(existing.updatedAt, incoming.updatedAt))
                }
            }

            val incomingAttachments = attachmentsByCode[incoming.code].orEmpty().sortedBy { it.position }
            var addedForLink = 0
            for (att in incomingAttachments) {
                val existingAtt = getRawAttachment(att.id)
                if (existingAtt == null) {
                    val nextPos = maxPosition(incoming.code) + 1
                    insertAttachments(listOf(att.copy(linkCode = incoming.code, position = nextPos, deletedAt = null)))
                    addedAttachments++
                    addedForLink++
                } else if (existingAtt.deletedAt != null) {
                    restoreAttachment(att.id)
                    addedAttachments++
                    addedForLink++
                }
            }
            if (existing != null && addedForLink > 0) {
                touch(incoming.code, max(existing.updatedAt, incoming.updatedAt))
            }
        }
        return MergeCounts(addedLinks = addedLinks, addedAttachments = addedAttachments)
    }
}

data class PurgeResult(val deletedCount: Int, val filePaths: List<String>)
