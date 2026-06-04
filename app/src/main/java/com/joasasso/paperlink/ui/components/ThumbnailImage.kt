package com.joasasso.paperlink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.graphicsLayer
import coil3.compose.AsyncImage
import com.joasasso.paperlink.data.local.ContentType
import com.joasasso.paperlink.ui.theme.ColorUtils
import com.joasasso.paperlink.util.YouTubeUtils
import androidx.core.net.toUri

/**
 * Miniatura "Visual-First".
 * Si es Imagen/Video, muestra el archivo.
 * Si es WEB_LINK de YouTube, muestra la miniatura del video.
 * Si es WEB_LINK genérico, muestra un gradiente con el Favicon.
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
    val isVisualResource = (type == ContentType.IMAGE || type == ContentType.VIDEO || type == ContentType.PDF)
    val isWebLink = type == ContentType.WEB_LINK
    
    // Generación del Fingerprint Visual (Gradiente dinámico)
    val brush = remember(code) {
        val colors = ColorUtils.getAestheticColors(code)
        Brush.linearGradient(colors)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(brush), // El gradiente siempre está de fondo
        contentAlignment = Alignment.Center,
    ) {
        if (isVisualResource && !uri.isNullOrBlank()) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = rememberVectorPainter(icon),
            )
        } else if (isWebLink && !uri.isNullOrBlank()) {
            val youtubeThumbnail = remember(uri) { YouTubeUtils.getYouTubeThumbnail(uri) }

            if (youtubeThumbnail != null) {
                // Caso especializado para YouTube: Miniatura a pantalla completa
                // Aplicamos un zoom (1.35f) para eliminar las barras negras del hqdefault.jpg
                AsyncImage(
                    model = youtubeThumbnail,
                    contentDescription = "YouTube Thumbnail",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(scaleX = 1.35f, scaleY = 1.35f),
                    contentScale = ContentScale.Crop,
                    error = rememberVectorPainter(icon)
                )
            } else {
                // Favicon de alta resolución centrado sobre el gradiente para el resto de webs
                val domain = remember(uri) {
                    try {
                        val host = uri.toUri().host ?: ""
                        val cleanHost = if (host.startsWith("www.")) host.substring(4) else host
                        cleanHost
                    } catch (_: Exception) {
                        ""
                    }
                }
                
                if (domain.isNotEmpty()) {
                    val faviconUrl = "https://www.google.com/s2/favicons?domain=$domain&sz=128"
                    AsyncImage(
                        model = faviconUrl,
                        contentDescription = "Favicon",
                        modifier = Modifier.size(64.dp),
                        contentScale = ContentScale.Fit,
                        error = rememberVectorPainter(icon)
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxSize(0.4f)
                    )
                }
            }
        } else {
            // Icono sutil sobre el gradiente para Notas y otros casos
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxSize(0.4f)
            )
        }
    }
}
