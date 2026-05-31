package com.joasasso.paperlink.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO de [PaperLink].
 *
 * Importante:
 * - [insert] usa `OnConflictStrategy.ABORT`. Si se intenta insertar un código que
 *   ya existe, Room tira `SQLiteConstraintException`. Esto es deliberado: el
 *   `GenerateCodeUseCase` (Fase 3) debe verificar unicidad antes, y un fallo acá
 *   indicaría un bug que NO queremos enmascarar pisando datos.
 * - Las consultas que alimentan UI reactiva devuelven [Flow]. Las puntuales son
 *   `suspend`.
 * - El parámetro `code` se asume ya normalizado a mayúsculas por el repositorio.
 */
@Dao
interface PaperLinkDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(link: PaperLink): Long

    @Query("DELETE FROM paper_links WHERE code = :code")
    suspend fun deleteByCode(code: String): Int

    @Query("SELECT * FROM paper_links WHERE code = :code")
    suspend fun getByCode(code: String): PaperLink?

    @Query("SELECT * FROM paper_links WHERE code = :code")
    fun observeByCode(code: String): Flow<PaperLink?>

    @Query("SELECT EXISTS(SELECT 1 FROM paper_links WHERE code = :code)")
    suspend fun existsByCode(code: String): Boolean

    @Query("SELECT * FROM paper_links ORDER BY created_at DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<PaperLink>>

    @Query("SELECT COUNT(*) FROM paper_links")
    suspend fun count(): Int

    /*
    // TODO: Define PaperLinkFts entity to use this search method
    @Query("""
        SELECT * FROM paper_links 
        JOIN paper_links_fts ON paper_links.code = paper_links_fts.code 
        WHERE paper_links_fts MATCH :searchQuery
    """)
    fun searchLinksFts(searchQuery: String): Flow<List<PaperLink>>
    */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubject(subject: Subject): Long

    @Update
    suspend fun updateSubject(subject: Subject)

    @Delete
    suspend fun deleteSubject(subject: Subject)

    @Query("SELECT * FROM subjects")
    fun getAllSubjects(): Flow<List<Subject>>

    // Método base para la limpieza de referencias huérfanas
    @Query("SELECT * FROM paper_links")
    suspend fun getAllLinksSnapshot(): List<PaperLink>
}
