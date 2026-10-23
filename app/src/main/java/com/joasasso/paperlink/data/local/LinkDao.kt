package com.joasasso.paperlink.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

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
    suspend fun purgeDeletedBefore(cutoff: Long): List<String> {
        val paths = filesOfDeletedBefore(cutoff)
        deleteLinksDeletedBefore(cutoff)
        return paths
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
    suspend fun purgeAttachmentsDeletedBefore(cutoff: Long): List<String> {
        val paths = filesOfAttachmentsDeletedBefore(cutoff)
        deleteAttachmentsDeletedBefore(cutoff)
        return paths
    }
}
