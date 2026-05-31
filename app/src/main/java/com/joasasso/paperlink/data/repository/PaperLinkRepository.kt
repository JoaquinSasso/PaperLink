package com.joasasso.paperlink.data.repository

import com.joasasso.paperlink.data.local.ContentType
import com.joasasso.paperlink.data.local.PaperLink
import com.joasasso.paperlink.data.local.PaperLinkDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repositorio simplificado para el pivote Visual-First.
 */
class PaperLinkRepository(
    private val dao: PaperLinkDao
) {

    suspend fun insert(link: PaperLink): Long = withContext(Dispatchers.IO) {
        dao.insert(link.copy(code = link.code.uppercase()))
    }

    /**
     * Borra un vínculo y su archivo físico si aplica.
     */
    suspend fun delete(link: PaperLink, filesDir: File): Boolean = withContext(Dispatchers.IO) {
        if (link.contentType == ContentType.TEXT_NOTE) {
            try {
                val file = File(File(filesDir, "notes"), "nota_${link.code}.txt")
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                // Silencioso
            }
        }
        dao.deleteByCode(link.code.uppercase()) > 0
    }

    suspend fun getByCode(code: String): PaperLink? = withContext(Dispatchers.IO) {
        dao.getByCode(code.uppercase())
    }

    suspend fun exists(code: String): Boolean = withContext(Dispatchers.IO) {
        dao.existsByCode(code.uppercase())
    }

    /**
     * Observa todos los vínculos para la grilla principal.
     */
    fun getAllLinks(): Flow<List<PaperLink>> {
        return dao.getAllLinks()
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        dao.count()
    }
}
