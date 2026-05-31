package com.joasasso.paperlink.data.local

import androidx.room.TypeConverter

/**
 * Tipos de contenido que un [PaperLink] puede vincular.
 *
 * Decisiones de diseño:
 * - Cada tipo local (todos menos WEB_LINK) almacena una `content://` URI obtenida
 *   vía SAF (ACTION_OPEN_DOCUMENT) con `takePersistableUriPermission`.
 * - WEB_LINK almacena una URL `https://...` o `http://...` en el campo `contentUri`.
 * - FILE es un catch-all para archivos locales cuyo MIME no encaja en las otras
 *   categorías o cuyo tipo no nos importa diferenciar.
 *
 * Persistencia:
 * - Se guarda como String (el nombre del enum), no como ordinal. Esto blinda el
 *   schema contra reordenamientos o inserciones de valores nuevos en el enum.
 */
enum class ContentType {
    IMAGE,      // Fotografías capturadas o de la galería (jpg, png)
    VIDEO,      // Archivos de video (mp4, mkv)
    AUDIO,      // Archivos de sonido o grabaciones de clases (mp3, m4a, wav)
    PDF,        // Documentos de texto portátiles (.pdf)
    WEB_LINK,   // Enlaces a internet (https://...)
    FILE,       // Tipo genérico para archivos no catalogados (zip, docx, etc.)
    TEXT_NOTE;   // Apuntes de texto largos escritos nativamente dentro de la app

    companion object {
        /**
         * Conversión defensiva desde String. Si el nombre no matchea ninguno de
         * los valores (por ejemplo, un dato corrupto en la base), devuelve FILE
         * como fallback razonable: la app va a poder seguir abriendo el recurso
         * delegando al sistema con un MIME type genérico.
         */
        fun fromStringOrFallback(raw: String?): ContentType {
            if (raw == null) return FILE
            return entries.firstOrNull { it.name == raw } ?: FILE
        }
    }
}

/**
 * TypeConverter de Room para [ContentType]. Se registra en [PaperLinkDatabase].
 */
class ContentTypeConverter {

    @TypeConverter
    fun fromContentType(value: ContentType): String = value.name

    @TypeConverter
    fun toContentType(value: String): ContentType = ContentType.fromStringOrFallback(value)
}
