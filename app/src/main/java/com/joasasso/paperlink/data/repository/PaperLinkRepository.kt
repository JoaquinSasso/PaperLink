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
     * Borra un vínculo y su archivo físico si reside en el espacio interno de la app.
     */
    suspend fun delete(link: PaperLink, filesDir: File, cacheDir: File): Boolean = withContext(Dispatchers.IO) {
        try {
            // Caso 1: Nota de texto (siempre interna)
            if (link.contentType == ContentType.TEXT_NOTE) {
                val file = File(File(filesDir, "notes"), "nota_${link.code}.txt")
                if (file.exists()) file.delete()
            } 
            // Caso 2: Cualquier otro contenido que resida en filesDir o cacheDir interno
            else {
                val uriString = link.contentUri
                if (uriString.startsWith("file://") || uriString.startsWith("content://")) {
                    // Intentamos determinar si la ruta física está dentro de la app
                    // Para URIs de FileProvider, esto es más complejo, pero si es un archivo directo:
                    val internalPath = filesDir.absolutePath
                    val cachePath = cacheDir.absolutePath
                    
                    // Si la URI contiene el nombre del paquete o rutas internas conocidas
                    if (uriString.contains(internalPath) || uriString.contains(cachePath) || uriString.contains("com.joasasso.paperlink")) {
                        // Nota: Solo borramos si NO es MediaStore (galería compartida)
                        if (!uriString.contains("media/external")) {
                            // Aquí iría la lógica de borrado físico si logramos resolver el File
                            // Por ahora, manejamos las notas y fotos temporales de cámara.
                            if (uriString.contains("camera_temp") || uriString.contains("cache/camera")) {
                                // Borrado de fotos temporales de cámara
                                val fileName = uriString.substringAfterLast("/")
                                val tempFile = File(File(cacheDir, "camera"), fileName)
                                if (tempFile.exists()) tempFile.delete()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Silencioso
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
