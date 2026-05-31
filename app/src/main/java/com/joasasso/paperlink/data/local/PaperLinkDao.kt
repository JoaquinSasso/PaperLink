package com.joasasso.paperlink.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO radical para PaperLink.
 * Solo operaciones puras de código <-> recurso.
 */
@Dao
interface PaperLinkDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(link: PaperLink): Long

    @Query("DELETE FROM paper_links WHERE code = :code")
    suspend fun deleteByCode(code: String): Int

    @Query("SELECT * FROM paper_links WHERE code = :code")
    suspend fun getByCode(code: String): PaperLink?

    @Query("SELECT EXISTS(SELECT 1 FROM paper_links WHERE code = :code)")
    suspend fun existsByCode(code: String): Boolean

    /**
     * Devuelve todos los vínculos ordenados por fecha. 
     * En el diseño Visual-First, la grilla muestra todo o casi todo.
     */
    @Query("SELECT * FROM paper_links ORDER BY created_at DESC")
    fun getAllLinks(): Flow<List<PaperLink>>

    @Query("SELECT COUNT(*) FROM paper_links")
    suspend fun count(): Int
}
