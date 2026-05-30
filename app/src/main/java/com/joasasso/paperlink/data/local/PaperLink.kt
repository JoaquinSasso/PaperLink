package com.joasasso.paperlink.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Vínculo entre un código de 4 caracteres y un recurso digital de cualquier tipo.
 *
 * - [code]: clave primaria. Siempre se almacena en mayúsculas. El alfabeto válido
 *   se valida en la capa de dominio (Fase 3), no a nivel de base de datos.
 * - [contentType]: tipo de contenido vinculado. Determina cómo la UI presenta
 *   la referencia y con qué Intent se abre.
 * - [contentUri]: para tipos locales, una `content://` URI persistente obtenida
 *   vía SAF. Para [ContentType.WEB_LINK], una URL externa.
 * - [displayName]: nombre legible del recurso, autocompletado cuando es posible
 *   (nombre de archivo en SAF, título de página o último segmento de URL en
 *   web links). Es opcional: si no se pudo obtener, queda null.
 * - [note]: nota opcional escrita por el usuario (máx. 200 caracteres, validado
 *   en UI).
 * - [createdAt]: timestamp en milisegundos desde epoch. Se usa para ordenar las
 *   referencias más recientes en HomeScreen.
 * - [subject]: nullable. Reservado para una versión futura donde se agrupen
 *   referencias por materia. En el MVP siempre es null.
 *
 * Nota sobre miniaturas: para los tipos IMAGE y VIDEO, Coil 3 puede generar
 * thumbnails directamente desde [contentUri], así que NO se guarda un campo
 * separado de miniatura. Para los otros tipos se muestra un ícono según el tipo.
 */
@Entity(tableName = "paper_links")
data class PaperLink(
    @PrimaryKey val code: String,
    @ColumnInfo(name = "content_type") val contentType: ContentType,
    @ColumnInfo(name = "content_uri") val contentUri: String,
    @ColumnInfo(name = "display_name") val displayName: String? = null,
    val note: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "subject_id") val subjectId: Long? = null
)
