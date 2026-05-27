package com.joasasso.paperlink.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO de [PaperReference].
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
interface PaperReferenceDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(reference: PaperReference)

    @Delete
    suspend fun delete(reference: PaperReference)

    @Query("DELETE FROM paper_references WHERE code = :code")
    suspend fun deleteByCode(code: String): Int

    @Query("SELECT * FROM paper_references WHERE code = :code LIMIT 1")
    suspend fun getByCode(code: String): PaperReference?

    @Query("SELECT * FROM paper_references WHERE code = :code LIMIT 1")
    fun observeByCode(code: String): Flow<PaperReference?>

    @Query("SELECT EXISTS(SELECT 1 FROM paper_references WHERE code = :code LIMIT 1)")
    suspend fun existsByCode(code: String): Boolean

    @Query("SELECT * FROM paper_references ORDER BY created_at DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<PaperReference>>

    @Query("SELECT COUNT(*) FROM paper_references")
    suspend fun count(): Int
}
