package com.joasasso.paperlink.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
    // ... [Mantener los métodos anteriores: insert, delete, getByCode, etc.]

    @Query("""
        SELECT * FROM paper_links 
        JOIN paper_links_fts ON paper_links.code = paper_links_fts.code 
        WHERE paper_links_fts MATCH :searchQuery
    """)
    fun searchLinksFts(searchQuery: String): Flow<List<PaperLink>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubject(subject: Subject)

    @Query("SELECT * FROM subjects")
    fun getAllSubjects(): Flow<List<Subject>>

    // Método base para la limpieza de referencias huérfanas
    @Query("SELECT * FROM paper_links")
    suspend fun getAllLinksSnapshot(): List<PaperLink>
}
