package com.joasasso.paperlink.util

/**
 * Utilidad para detectar enlaces de YouTube y extraer sus miniaturas.
 * Permite una experiencia "Visual-First" inmediata sin web scraping.
 */
object YouTubeUtils {
    /**
     * Regex robusto para detectar IDs de YouTube de 11 caracteres.
     * Soporta:
     * - https://www.youtube.com/watch?v=VIDEO_ID
     * - https://youtu.be/VIDEO_ID
     * - https://youtube.com/embed/VIDEO_ID
     * - https://youtube.com/v/VIDEO_ID
     */
    private val youtubeRegex = ("""(?:https?://)?(?:www\.)?""" +
            """(?:youtube\.com/(?:watch\?v=|embed/|v/|shorts/)|youtu\.be/)""" +
            """([a-zA-Z0-9_-]{11})""").toRegex()

    /**
     * Devuelve la URL de la miniatura de alta calidad si la URL es de YouTube.
     * HQDefault es el estándar de 480x360, ideal para la grilla.
     */
    fun getYouTubeThumbnail(url: String): String? {
        val matchResult = youtubeRegex.find(url)
        val videoId = matchResult?.groupValues?.get(1)
        return videoId?.let { "https://img.youtube.com/vi/$it/hqdefault.jpg" }
    }
}
