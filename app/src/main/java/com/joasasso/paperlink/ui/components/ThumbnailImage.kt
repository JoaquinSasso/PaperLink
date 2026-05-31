package com.joasasso.paperlink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.joasasso.paperlink.data.local.ContentType
import kotlin.math.absoluteValue

/**
 * Miniatura "Visual-First".
 * Si es Imagen/Video, muestra el archivo.
 * Si es otro tipo, genera un gradiente único basado en el código (Visual Fingerprint).
 */
@Composable
fun ThumbnailImage(
    uri: String?,
    type: ContentType,
    code: String,
    modifier: Modifier = Modifier,
) {
    val icon = ContentTypeIcons.getIcon(type)
    val showImage = (type == ContentType.IMAGE || type == ContentType.VIDEO || type == ContentType.PDF)
    
    // Generación del Fingerprint Visual (Gradiente dinámico)
    val brush = remember(code) {
        val hash = code.hashCode()
        val color1 = Color(0xFF000000 or (hash.absoluteValue.toLong() and 0xFFFFFF))
        val color2 = Color(0xFF000000 or ((hash * 31).absoluteValue.toLong() and 0xFFFFFF))
        Brush.linearGradient(listOf(color1, color2))
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(brush), // El gradiente siempre está de fondo
        contentAlignment = Alignment.Center,
    ) {
        if (showImage && !uri.isNullOrBlank()) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                // Si falla la carga (ej: PDF corrupto o imagen borrada), 
                // dejamos que se vea el gradiente con el icono encima.
                error = rememberVectorPainter(icon),
                alpha = 1f
            )
        } else {
            // Icono sutil sobre el gradiente para Notas y URLs
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxSize(0.4f)
            )
        }
    }
}
