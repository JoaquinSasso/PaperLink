package com.joasasso.paperlink.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.joasasso.paperlink.data.local.ContentType

/**
 * Mapeo centralizado de [ContentType] a [ImageVector] de Material Icons.
 * Estos iconos se utilizan en toda la aplicación (Creación, Lista, Detalle)
 * para proporcionar una identidad visual clara a cada tipo de recurso.
 */
object ContentTypeIcons {
    fun getIcon(type: ContentType?): ImageVector = when (type) {
        ContentType.IMAGE -> Icons.Default.Image
        ContentType.VIDEO -> Icons.Default.Movie
        ContentType.AUDIO -> Icons.Default.Audiotrack
        ContentType.PDF -> Icons.Default.PictureAsPdf
        ContentType.WEB_LINK -> Icons.Default.Language
        ContentType.TEXT_NOTE -> Icons.Default.Description
        ContentType.FILE -> Icons.AutoMirrored.Filled.InsertDriveFile
        null -> Icons.Default.QuestionMark
    }
}
