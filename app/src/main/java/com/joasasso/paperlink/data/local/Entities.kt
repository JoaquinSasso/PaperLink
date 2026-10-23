package com.joasasso.paperlink.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Un código de 4 caracteres escrito en papel.
 *
 * - [code]: clave primaria, siempre en mayúsculas.
 * - [label]: etiqueta opcional; nunca obligatoria.
 * - [deletedAt]: borrado lógico. Mientras no sea null el código no aparece en ninguna consulta
 *   y puede restaurarse; la purga física lo elimina pasada la ventana de deshacer.
 */
@Entity(tableName = "links")
data class Link(
    @PrimaryKey val code: String,
    val label: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

/**
 * Un recurso vinculado a un código.
 *
 * Según [kind] se usa un solo campo de contenido: [relativePath] para archivos
 * (`media/<uuid>.<ext>` dentro de `filesDir`), [url] para links web y [text] para notas.
 *
 * [deletedAt]: borrado lógico de un elemento suelto (versión 4), con la misma ventana de
 * deshacer que los códigos.
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = Link::class,
            parentColumns = ["code"],
            childColumns = ["linkCode"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("linkCode")],
)
data class Attachment(
    @PrimaryKey val id: String,
    val linkCode: String,
    val kind: Kind,
    val relativePath: String?,
    val url: String?,
    val text: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
    val sha256: String?,
    val position: Int,
    val createdAt: Long,
    val deletedAt: Long? = null,
) {
    enum class Kind { IMAGE, VIDEO, AUDIO, PDF, FILE, URL, NOTE }
}

data class LinkWithAttachments(
    @Embedded val link: Link,
    @Relation(parentColumn = "code", entityColumn = "linkCode")
    val attachments: List<Attachment>,
)
