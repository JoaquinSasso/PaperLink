package com.joasasso.paperlink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.joasasso.paperlink.data.local.ContentType

/**
 * Componente de miniatura que decide si mostrar una imagen (vía Coil)
 * o un icono representativo basado en el [ContentType].
 *
 * @param uri La URI del recurso (local o remota).
 * @param type El tipo de contenido para determinar el icono de fallback.
 * @param modifier Modificador de Compose para tamaño y estilo.
 */
@Composable
fun ThumbnailImage(
    uri: String?,
    type: ContentType,
    modifier: Modifier = Modifier,
) {
    val icon = ContentTypeIcons.getIcon(type)
    val showImage = (type == ContentType.IMAGE || type == ContentType.VIDEO)
    val fallbackPainter = rememberVectorPainter(icon)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (showImage && !uri.isNullOrBlank()) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = fallbackPainter,
                placeholder = fallbackPainter
            )
        } else {
            // Para PDFs, Enlaces, Notas, etc., mostramos el icono centralizado escalado
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxSize(0.4f)
            )
        }
    }
}
