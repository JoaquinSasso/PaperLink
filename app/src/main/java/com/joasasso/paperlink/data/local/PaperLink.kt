package com.joasasso.paperlink.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Vínculo radical entre un código de 4 caracteres y un recurso digital.
 * Diseño "Visual-First" de Fricción Cero: se eliminan metadatos (títulos, notas, materias).
 *
 * - [code]: clave primaria. Siempre en mayúsculas.
 * - [contentType]: tipo de contenido (Imagen, Video, PDF, etc).
 * - [contentUri]: URI de SAF o URL web.
 * - [createdAt]: timestamp para ordenación en la grilla.
 */
@Entity(tableName = "paper_links")
data class PaperLink(
    @PrimaryKey val code: String,
    @ColumnInfo(name = "content_type") val contentType: ContentType,
    @ColumnInfo(name = "content_uri") val contentUri: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
