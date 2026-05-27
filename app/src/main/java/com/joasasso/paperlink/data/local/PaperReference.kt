package com.joasasso.paperlink.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Representa una referencia entre un código de 4 caracteres y una foto.
 *
 * - [code]: clave primaria. Siempre se almacena en mayúsculas. El alfabeto
 *   válido se valida en la capa de dominio (Fase 3), no a nivel de base de datos.
 * - [photoUri]: URI persistente obtenida vía SAF (ACTION_OPEN_DOCUMENT) con
 *   `takePersistableUriPermission`. Se almacena como String para portabilidad.
 * - [note]: nota opcional escrita por el usuario (máx. 200 caracteres, validado en UI).
 * - [createdAt]: timestamp en milisegundos desde epoch. Se usa para ordenar
 *   las referencias más recientes en HomeScreen.
 * - [subject]: nullable. Reservado para una versión futura donde se agrupen
 *   referencias por materia. En el MVP siempre es null.
 */
@Entity(tableName = "paper_references")
data class PaperReference(
    @PrimaryKey
    @ColumnInfo(name = "code")
    val code: String,

    @ColumnInfo(name = "photo_uri")
    val photoUri: String,

    @ColumnInfo(name = "note")
    val note: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "subject")
    val subject: String? = null
)
