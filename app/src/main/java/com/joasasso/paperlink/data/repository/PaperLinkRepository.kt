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
    suspend fun delete(link: PaperLink, filesDir: File): Boolean = withContext(Dispatchers.IO) {
        try {
            // Caso 1: Nota de texto (siempre interna)
            if (link.contentType == ContentType.TEXT_NOTE) {
                val file = File(File(filesDir, "notes"), "nota_${link.code}.txt")
                if (file.exists()) file.delete()
            } 
            // Caso 2: Cualquier otro contenido que resida en el almacenamiento interno (filesDir)
            else {
                val uriString = link.contentUri
                if (uriString.startsWith("file://") || uriString.startsWith("content://")) {
                    val internalPath = filesDir.absolutePath
                    
                    // Si la URI contiene el nombre del paquete o rutas internas de filesDir
                    if (uriString.contains(internalPath) || uriString.contains("com.joasasso.paperlink")) {
                        // Nota: Solo borramos si NO es MediaStore (galería compartida)
                        if (!uriString.contains("media/external")) {
                            // Borrado de fotos de cámara interna
                            if (uriString.contains("camera_photos") || uriString.contains("files/camera")) {
                                val fileName = uriString.substringAfterLast("/")
                                val cameraFile = File(File(filesDir, "camera"), fileName)
                                if (cameraFile.exists()) cameraFile.delete()
                            }
                            // Borrado de medios compartidos (copias internas de Share Intent)
                            else if (uriString.contains("shared_media") || uriString.contains("files/shared")) {
                                val fileName = uriString.substringAfterLast("/")
                                val sharedFile = File(File(filesDir, "shared"), fileName)
                                if (sharedFile.exists()) sharedFile.delete()
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
